package info.nightscout.androidaps.plugins.PumpInsight;

import android.os.Handler;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import info.nightscout.androidaps.BuildConfig;
import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.DetailedBolusInfo;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.data.PumpEnactResult;
import info.nightscout.androidaps.db.ExtendedBolus;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TemporaryBasal;
import info.nightscout.androidaps.interfaces.ConstraintsInterface;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PumpDescription;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.plugins.Overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.Overview.events.EventOverviewBolusProgress;
import info.nightscout.androidaps.plugins.Overview.notifications.Notification;
import info.nightscout.androidaps.plugins.PumpInsight.connector.AbsoluteTBRTaskRunner;
import info.nightscout.androidaps.plugins.PumpInsight.connector.CancelBolusTaskRunner;
import info.nightscout.androidaps.plugins.PumpInsight.connector.Connector;
import info.nightscout.androidaps.plugins.PumpInsight.connector.SetTBRTaskRunner;
import info.nightscout.androidaps.plugins.PumpInsight.events.EventInsightPumpCallback;
import info.nightscout.androidaps.plugins.PumpInsight.events.EventInsightPumpUpdateGui;
import info.nightscout.androidaps.plugins.PumpInsight.history.HistoryReceiver;
import info.nightscout.androidaps.plugins.PumpInsight.history.LiveHistory;
import info.nightscout.androidaps.plugins.PumpInsight.utils.Helpers;
import info.nightscout.androidaps.plugins.PumpInsight.utils.StatusItem;
import info.nightscout.utils.DateUtil;
import info.nightscout.utils.NSUpload;
import info.nightscout.utils.SP;
import sugar.free.sightparser.applayer.descriptors.ActiveBolus;
import sugar.free.sightparser.applayer.descriptors.ActiveBolusType;
import sugar.free.sightparser.applayer.descriptors.PumpStatus;
import sugar.free.sightparser.applayer.messages.AppLayerMessage;
import sugar.free.sightparser.applayer.messages.remote_control.BolusMessage;
import sugar.free.sightparser.applayer.messages.remote_control.CancelTBRMessage;
import sugar.free.sightparser.applayer.messages.remote_control.ExtendedBolusMessage;
import sugar.free.sightparser.applayer.messages.remote_control.StandardBolusMessage;
import sugar.free.sightparser.handling.SingleMessageTaskRunner;
import sugar.free.sightparser.handling.TaskRunner;
import sugar.free.sightparser.handling.taskrunners.StatusTaskRunner;

import static info.nightscout.androidaps.plugins.PumpInsight.history.PumpIdCache.getRecordUniqueID;
import static info.nightscout.androidaps.plugins.PumpInsight.utils.Helpers.roundDouble;


/**
 * Created by jamorham on 23/01/2018.
 *
 * Connects to SightRemote app service using SightParser library
 *
 * SightRemote and SightParser created by Tebbe Ubben
 *
 * Original proof of concept SightProxy by jamorham
 *
 */

@SuppressWarnings("AccessStaticViaInstance")
public class InsightPumpPlugin implements PluginBase, PumpInterface, ConstraintsInterface {

    private static final long BUSY_WAIT_TIME = 20000;
    static Integer batteryPercent = 0;
    static Integer reservoirInUnits = 0;
    static boolean initialized = false;
    private static volatile boolean update_pending = false;
    private static Logger log = LoggerFactory.getLogger(InsightPumpPlugin.class);
    private static volatile InsightPumpPlugin plugin;
    private final Handler handler = new Handler();
    private final InsightPumpAsyncAdapter async = new InsightPumpAsyncAdapter();
    private StatusTaskRunner.StatusResult statusResult;
    private long statusResultTime = -1;
    private Date lastDataTime = new Date(0);
    private TaskRunner taskRunner;
    private boolean fragmentEnabled = false;
    private boolean fragmentVisible = false;
    private boolean fauxTBRcancel = true;
    private PumpDescription pumpDescription = new PumpDescription();
    private double basalRate = 0;
    private Connector connector;
    private volatile boolean connector_enabled = false;
    private final TaskRunner.ResultCallback statusResultHandler = new TaskRunner.ResultCallback() {

        @Override
        public void onError(Exception e) {
            log("Got error taskrunner: " + e);
            android.util.Log.e("INSIGHTPUMP", "taskrunner stacktrace: ", e);

            if (e instanceof sugar.free.sightparser.error.DisconnectedError) {
                if (Helpers.ratelimit("insight-reconnect", 2)) {
                    Connector.connectToPump();
                    updateGui();
                }
            }
        }

        @Override
        public synchronized void onResult(Object result) {
            log("GOT STATUS RESULT!!!");
            statusResult = (StatusTaskRunner.StatusResult) result;
            statusResultTime = Helpers.tsl();
            processStatusResult();
            updateGui();
            connector.requestHistoryReSync();
            connector.requestHistorySync();
        }
    };

    private InsightPumpPlugin() {
        log("InsightPumpPlugin instantiated");
        pumpDescription.isBolusCapable = true;
        pumpDescription.bolusStep = 0.05d; // specification says 0.05U up to 2U then 0.1U @ 2-5U  0.2U @ 10-20U 0.5U 10-20U (are these just UI restrictions?)

        pumpDescription.isExtendedBolusCapable = true;
        pumpDescription.extendedBolusStep = 0.05d; // specification probably same as above
        pumpDescription.extendedBolusDurationStep = 15; // 15 minutes up to 24 hours
        pumpDescription.extendedBolusMaxDuration = 24 * 60;

        pumpDescription.isTempBasalCapable = true;
        //pumpDescription.tempBasalStyle = PumpDescription.PERCENT | PumpDescription.ABSOLUTE;
        pumpDescription.tempBasalStyle = PumpDescription.PERCENT;

        pumpDescription.maxTempPercent = 250; // 0-250%
        pumpDescription.tempPercentStep = 10;

        pumpDescription.tempDurationStep = 15; // 15 minutes up to 24 hours
        pumpDescription.tempMaxDuration = 24 * 60;

        pumpDescription.isSetBasalProfileCapable = false; // leave this for now
        pumpDescription.basalStep = 0.01d;
        pumpDescription.basalMinimumRate = 0.02d;

        pumpDescription.isRefillingCapable = true;
        //pumpDescription.storesCarbInfo = false;

    }


    public static InsightPumpPlugin getPlugin() {
        if (plugin == null) {
            createInstance();
        }
        return plugin;
    }

    private static synchronized void createInstance() {
        if (plugin == null) {
            log("creating instance");
            plugin = new InsightPumpPlugin();
        }
    }

    // just log during debugging
    private static void log(String msg) {
        android.util.Log.e("INSIGHTPUMP", msg);
    }

    private static void updateGui() {
        update_pending = false;
        MainApp.bus().post(new EventInsightPumpUpdateGui());
    }

    private static void pushCallbackEvent(EventInsightPumpCallback e) {
        MainApp.bus().post(e);
    }

    private void enableConnector() {
        if (!connector_enabled) {
            synchronized (this) {
                if (!connector_enabled) {
                    log("Instantiating connector");
                    connector_enabled = true;
                    this.connector = Connector.get();
                    this.connector.init();
                }
            }
        }
    }

    private void disableConnector() {
        if (connector_enabled) {
            synchronized (this) {
                if (connector_enabled) {
                    log("Shutting down connector");
                    Connector.get().shutdown();
                    connector_enabled = false;
                }
            }
        }
    }

    @Override
    public String getFragmentClass() {
        return InsightPumpFragment.class.getName();
    }

    @Override
    public String getName() {
        return MainApp.instance().getString(R.string.insightpump);
    }

    @Override
    public String getNameShort() {
        String name = MainApp.instance().getString(R.string.insightpump_shortname);
        if (!name.trim().isEmpty()) {
            //only if translation exists
            return name;
        }
        // use long name as fallback
        return getName();
    }

    @Override
    public boolean isEnabled(int type) {
        if (type == PluginBase.PUMP) return fragmentEnabled;
        else if (type == PluginBase.CONSTRAINTS) return fragmentEnabled;
        return false;
    }

    @Override
    public boolean isVisibleInTabs(int type) {
        return type == PUMP && fragmentVisible;
    }

    @Override
    public boolean canBeHidden(int type) {
        return true;
    }

    @Override
    public boolean hasFragment() {
        return true;
    }

    @Override
    public boolean showInList(int type) {
        return type == PUMP;
    }

    @Override
    public void setFragmentEnabled(int type, boolean fragmentEnabled) {
        if (type == PUMP) {
            if (fragmentEnabled) {
                enableConnector();
            } else {
                disableConnector();
            }
            this.fragmentEnabled = fragmentEnabled;
        }
    }

    @Override
    public void setFragmentVisible(int type, boolean fragmentVisible) {
        if (type == PUMP) this.fragmentVisible = fragmentVisible;
    }

    @Override
    public int getPreferencesId() {
        return R.xml.pref_insightpump;
    }

    @Override
    public int getType() {
        return PluginBase.PUMP;
    }

    @Override
    public boolean isFakingTempsByExtendedBoluses() {
        return false;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public boolean isSuspended() {
        return !isPumpRunning();
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    @Override
    public boolean isConnected() {
        return Connector.get().isPumpConnected();
    }

    @Override
    public boolean isConnecting() {
        return Connector.get().isPumpConnecting();
    }

    @Override
    public void connect(String reason) {
        log("InsightPumpPlugin::connect()");
        try {
            if (!connector.isPumpConnected()) {
                if (Helpers.ratelimit("insight-connect-timer", 40)) {
                    log("Actually requesting a connect");
                    connector.connectToPump();
                }
            } else {
                log("Already connected");
            }
        } catch (NullPointerException e) {
            log("Could not sconnect - null pointer: " + e);
        }

        // TODO review
        if (!Config.NSCLIENT && !Config.G5UPLOADER)
            NSUpload.uploadDeviceStatus();
        lastDataTime = new Date();
    }

    @Override
    public void disconnect(String reason) {
        log("InsightPumpPlugin::disconnect()");
        try {
            if (!SP.getBoolean("insight_always_connected", false)) {
                log("Requesting disconnect");
                connector.disconnectFromPump();
            } else {
                log("Not disconnecting due to preference");
            }
        } catch (NullPointerException e) {
            log("Could not disconnect - null pointer: " + e);
        }
    }

    @Override
    public void stopConnecting() {
        log("InsightPumpPlugin::stopConnecting()");
        try {
            if (isConnecting()) {
                if (!SP.getBoolean("insight_always_connected", false)) {
                    log("Requesting disconnect");
                    connector.disconnectFromPump();
                } else {
                    log("Not disconnecting due to preference");
                }
            } else {
                log("Not currently trying to connect so not stopping connection");
            }
        } catch (NullPointerException e) {
            log("Could not stop connecting - null pointer: " + e);
        }
    }

    @Override
    public void getPumpStatus() {

        log("getPumpStatus");
        lastDataTime = new Date();
        if (Connector.get().isPumpConnected()) {
            log("is connected.. requesting status");
            handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        taskRunner = new StatusTaskRunner(connector.getServiceConnector());
                                        taskRunner.fetch(statusResultHandler);
                                    }
                                }
                    , 1000);
        } else {
            log("not connected.. not requesting status");
        }
    }

    // TODO implement
    @Override
    public PumpEnactResult setNewBasalProfile(Profile profile) {
        lastDataTime = new Date();
        // Do nothing here. we are using MainApp.getConfigBuilder().getActiveProfile().getProfile();
        PumpEnactResult result = new PumpEnactResult();
        result.enacted = false;
        result.success = false;
        Notification notification = new Notification(Notification.PROFILE_SET_OK, MainApp.sResources.getString(R.string.profile_set_ok), Notification.INFO, 60);
        MainApp.bus().post(new EventNewNotification(notification));
        return result;
    }

    @Override
    public boolean isThisProfileSet(Profile profile) {
        return true;
    }

    @Override
    public Date lastDataTime() {
        return lastDataTime;
    }

    @Override
    public double getBaseBasalRate() {
        return basalRate;
    }

    public String getBaseBasalRateString() {
        final DecimalFormat df = new DecimalFormat("#.##");
        return df.format(basalRate);
    }

    @Override
    public PumpEnactResult deliverTreatment(DetailedBolusInfo detailedBolusInfo) {
        final PumpEnactResult result = new PumpEnactResult();
        result.bolusDelivered = detailedBolusInfo.insulin;
        result.carbsDelivered = detailedBolusInfo.carbs;
        result.enacted = result.bolusDelivered > 0 || result.carbsDelivered > 0;
        result.comment = MainApp.instance().getString(R.string.virtualpump_resultok);

        result.percent = 100;

        // is there an insulin component to the treatment?
        if (detailedBolusInfo.insulin > 0) {
            final UUID cmd = deliverBolus((float) detailedBolusInfo.insulin); // actually request delivery
            if (cmd == null) {
                return pumpEnactFailure();
            }
            final Mstatus ms = async.busyWaitForCommandResult(cmd, BUSY_WAIT_TIME);

            result.success = ms.success();
            if (ms.success()) {
                detailedBolusInfo.pumpId = getRecordUniqueID(ms.getResponseID());
            }
        } else {
            result.success = true; // always true with carb only treatments
        }

        if (result.success) {
            log("Success!");

            final EventOverviewBolusProgress bolusingEvent = EventOverviewBolusProgress.getInstance();
            bolusingEvent.status = String.format(MainApp.sResources.getString(R.string.bolusdelivered), detailedBolusInfo.insulin);
            bolusingEvent.percent = 100;
            MainApp.bus().post(bolusingEvent);
            MainApp.getConfigBuilder().addToHistoryTreatment(detailedBolusInfo);
        } else {
            log.debug("Failure to deliver treatment");
        }

        if (Config.logPumpComm)
            log.debug("Delivering treatment insulin: " + detailedBolusInfo.insulin + "U carbs: " + detailedBolusInfo.carbs + "g " + result);

        updateGui();
        connector.tryToGetPumpStatusAgain();

        lastDataTime = new Date();
        connector.requestHistorySync(30000);
        return result;
    }

    @Override
    public void stopBolusDelivering() {
        final UUID cmd = aSyncTaskRunner(new CancelBolusTaskRunner(connector.getServiceConnector(), ActiveBolusType.STANDARD), "Cancel standard bolus");

        if (cmd == null) {
            return;
        }

        final Mstatus cs = async.busyWaitForCommandResult(cmd, BUSY_WAIT_TIME);
        log("Got command status: " + cs);
    }

    // Temporary Basals

    @Override
    public PumpEnactResult setTempBasalAbsolute(Double absoluteRate, Integer durationInMinutes, boolean enforceNew) {
        absoluteRate = Helpers.roundDouble(absoluteRate, 3);
        log("Set TBR absolute: " + absoluteRate);
        final double base_basal = getBaseBasalRate();
        if (base_basal == 0) {
            log("Base basal rate appears to be zero!");
            return pumpEnactFailure();
        }
        int percent_amount = (int) Math.round(100d / base_basal * absoluteRate);
        log("Calculated requested rate: " + absoluteRate + " base rate: " + base_basal + " percentage: " + percent_amount + "%");
        percent_amount = (int) Math.round(((double) percent_amount) / 10d) * 10;
        log("Calculated final rate: " + percent_amount + "%");

        if (percent_amount == 100) {
            return cancelTempBasal(false);
        }

        if (percent_amount > 250) percent_amount = 250;

      

        final SetTBRTaskRunner task = new SetTBRTaskRunner(connector.getServiceConnector(), percent_amount, durationInMinutes);
        final UUID cmd = aSyncTaskRunner(task, "Set TBR abs: " + absoluteRate + " " + durationInMinutes + "m");

        if (cmd == null) {
            return pumpEnactFailure();
        }

        Mstatus ms = async.busyWaitForCommandResult(cmd, BUSY_WAIT_TIME);
        log("Got command status: " + ms);

        PumpEnactResult pumpEnactResult = new PumpEnactResult().enacted(true).isPercent(true).duration(durationInMinutes);
        pumpEnactResult.percent = percent_amount;
        pumpEnactResult.success = ms.success();
        pumpEnactResult.isTempCancel = percent_amount == 100; // 100% temp basal is a cancellation
        pumpEnactResult.comment = ms.getCommandComment();


        if (pumpEnactResult.success) {
            // create log entry
            final TemporaryBasal tempBasal = new TemporaryBasal();
            tempBasal.date = System.currentTimeMillis();
            tempBasal.isAbsolute = false;
            tempBasal.percentRate = percent_amount;
            tempBasal.durationInMinutes = durationInMinutes;
            tempBasal.source = Source.USER;
            MainApp.getConfigBuilder().addToHistoryTempBasal(tempBasal);
        }

        if (Config.logPumpComm)
            log.debug("Setting temp basal absolute: " + pumpEnactResult.success);

        lastDataTime = new Date();

        updateGui();

        connector.requestHistorySync(5000);
        connector.tryToGetPumpStatusAgain();

        return pumpEnactResult;
    }

    //@Override
    public PumpEnactResult setTempBasalAbsoluteOld(Double absoluteRate, Integer durationInMinutes, boolean enforceNew) {
        absoluteRate = Helpers.roundDouble(absoluteRate, 3);
        log("Set TBR absolute: " + absoluteRate);

        final AbsoluteTBRTaskRunner task = new AbsoluteTBRTaskRunner(connector.getServiceConnector(), absoluteRate, durationInMinutes);
        final UUID cmd = aSyncTaskRunner(task, "Set TBR abs: " + absoluteRate + " " + durationInMinutes + "m");

        if (cmd == null) {
            return pumpEnactFailure();
        }

        Mstatus ms = async.busyWaitForCommandResult(cmd, BUSY_WAIT_TIME);
        log("Got command status: " + ms);

        PumpEnactResult pumpEnactResult = new PumpEnactResult().enacted(true).isPercent(false).duration(durationInMinutes);
        pumpEnactResult.absolute = absoluteRate; // TODO get converted value?
        pumpEnactResult.success = ms.success();
        pumpEnactResult.isTempCancel = false; // do we test this here?
        pumpEnactResult.comment = ms.getCommandComment();

        if (pumpEnactResult.success) {
            // create log entry
            final TemporaryBasal tempBasal = new TemporaryBasal();
            tempBasal.date = System.currentTimeMillis();
            tempBasal.isAbsolute = true;
            tempBasal.absoluteRate = task.getCalculatedAbsolute(); // is this the correct figure to use?
            tempBasal.durationInMinutes = durationInMinutes;
            tempBasal.source = Source.USER;
            MainApp.getConfigBuilder().addToHistoryTempBasal(tempBasal);
        }

        if (Config.logPumpComm)
            log.debug("Setting temp basal absolute: " + pumpEnactResult.success);

        lastDataTime = new Date();

        updateGui();

        connector.requestHistorySync(5000);
        connector.tryToGetPumpStatusAgain();

        return pumpEnactResult;
    }


    @Override
    public PumpEnactResult setTempBasalPercent(Integer percent, Integer durationInMinutes, boolean enforceNew) {
        log("Set TBR %");

        percent = (int) Math.round(((double) percent) / 10d) * 10;
        if (percent == 100) {
            // This would cause a cancel if a tbr is in progress so treat as a cancel
            return cancelTempBasal(false);
        }


        final UUID cmd = aSyncTaskRunner(new SetTBRTaskRunner(connector.getServiceConnector(), percent, durationInMinutes), "Set TBR " + percent + "%" + " " + durationInMinutes + "m");

        if (cmd == null) {
            return pumpEnactFailure();
        }

        final Mstatus ms = async.busyWaitForCommandResult(cmd, BUSY_WAIT_TIME);
        log("Got command status: " + ms);

        PumpEnactResult pumpEnactResult = new PumpEnactResult().enacted(true).isPercent(true).duration(durationInMinutes);
        pumpEnactResult.percent = percent;
        pumpEnactResult.success = ms.success();
        pumpEnactResult.isTempCancel = percent == 100; // 100% temp basal is a cancellation
        pumpEnactResult.comment = ms.getCommandComment();

        if (pumpEnactResult.success) {
            // create log entry
            final TemporaryBasal tempBasal = new TemporaryBasal();
            tempBasal.date = System.currentTimeMillis();
            tempBasal.isAbsolute = false;
            tempBasal.percentRate = percent;
            tempBasal.durationInMinutes = durationInMinutes;
            tempBasal.source = Source.USER; // TODO check this is correct
            MainApp.getConfigBuilder().addToHistoryTempBasal(tempBasal);
        }

        updateGui();

        if (Config.logPumpComm)
            log.debug("Set temp basal " + percent + "% for " + durationInMinutes + "m");

        connector.requestHistorySync(5000);
        connector.tryToGetPumpStatusAgain();

        return pumpEnactResult;
    }


    @Override
    public PumpEnactResult cancelTempBasal(boolean enforceNew) {
        log("Cancel TBR");


        fauxTBRcancel = !SP.getBoolean("insight_real_tbr_cancel", false);

        final UUID cmd;

        if (fauxTBRcancel) {
            cmd = aSyncTaskRunner(new SetTBRTaskRunner(connector.getServiceConnector(), 100, 1), "Faux Cancel TBR - setting " + "90%" + " 1m");
        } else {
            cmd = aSyncSingleCommand(new CancelTBRMessage(), "Cancel Temp Basal");
        }
        if (cmd == null) {
            return pumpEnactFailure();
        }

        // TODO isn't conditional on one apparently being in progress only the history change
        boolean enacted = false;
        final Mstatus ms = async.busyWaitForCommandResult(cmd, BUSY_WAIT_TIME);

        if (MainApp.getConfigBuilder().isTempBasalInProgress()) {
            enacted = true;
            TemporaryBasal tempStop = new TemporaryBasal(System.currentTimeMillis());
            tempStop.source = Source.USER;
            MainApp.getConfigBuilder().addToHistoryTempBasal(tempStop);
        }
        lastDataTime = new Date();
        updateGui();
        if (Config.logPumpComm)
            log.debug("Canceling temp basal: "); // TODO get more info

        connector.requestHistorySync(5000);
        connector.tryToGetPumpStatusAgain();

        return new PumpEnactResult().success(ms.success()).enacted(true).isTempCancel(true);
    }


    // Extended Boluses

    @Override
    public PumpEnactResult setExtendedBolus(Double insulin, Integer durationInMinutes) {
        log("Set Extended bolus " + insulin + " " + durationInMinutes);
        ExtendedBolusMessage extendedBolusMessage = new ExtendedBolusMessage();
        extendedBolusMessage.setAmount((float) ((double) insulin));
        extendedBolusMessage.setDuration((short) ((int) durationInMinutes));
        final UUID cmd = aSyncSingleCommand(extendedBolusMessage, "Extended bolus U" + insulin + " mins:" + durationInMinutes);
        if (cmd == null) {
            return pumpEnactFailure();
        }

        final Mstatus ms = async.busyWaitForCommandResult(cmd, BUSY_WAIT_TIME);
        log("Got command status: " + ms);

        PumpEnactResult pumpEnactResult = new PumpEnactResult().enacted(true).bolusDelivered(insulin).duration(durationInMinutes);
        pumpEnactResult.success = ms.success();
        pumpEnactResult.comment = ms.getCommandComment();

        if (pumpEnactResult.success) {
            // create log entry
            final ExtendedBolus extendedBolus = new ExtendedBolus();
            extendedBolus.date = System.currentTimeMillis();
            extendedBolus.insulin = insulin;
            extendedBolus.durationInMinutes = durationInMinutes;
            extendedBolus.source = Source.USER;
            extendedBolus.pumpId = getRecordUniqueID(ms.getResponseID());
            MainApp.getConfigBuilder().addToHistoryExtendedBolus(extendedBolus);
        }

        if (Config.logPumpComm)
            log.debug("Setting extended bolus: " + insulin + " mins:" + durationInMinutes + " " + pumpEnactResult.comment);

        updateGui();

        connector.requestHistorySync(30000);
        connector.tryToGetPumpStatusAgain();

        return pumpEnactResult;
    }

    @Override
    public PumpEnactResult cancelExtendedBolus() {

        log("Cancel Extended bolus");

        // TODO note always sends cancel to pump but only changes history if present

        final UUID cmd = aSyncTaskRunner(new CancelBolusTaskRunner(connector.getServiceConnector(), ActiveBolusType.EXTENDED), "Cancel extended bolus");

        if (cmd == null) {
            return pumpEnactFailure();
        }

        final Mstatus ms = async.busyWaitForCommandResult(cmd, BUSY_WAIT_TIME);

        if (MainApp.getConfigBuilder().isInHistoryExtendedBoluslInProgress()) {
            ExtendedBolus exStop = new ExtendedBolus(System.currentTimeMillis());
            exStop.source = Source.USER;
            MainApp.getConfigBuilder().addToHistoryExtendedBolus(exStop);
        }

        if (Config.logPumpComm)
            log.debug("Cancel extended bolus:");

        updateGui();

        connector.requestHistorySync(5000);
        connector.tryToGetPumpStatusAgain();

        return new PumpEnactResult().success(ms.success()).enacted(true);
    }


    private synchronized UUID deliverBolus(float bolusValue) {
        log("DeliverBolus: " + bolusValue);

        if (bolusValue == 0) return null;
        if (bolusValue < 0) return null;

        // TODO check limits here or they already occur via a previous constraint interface?

        final StandardBolusMessage message = new StandardBolusMessage();
        message.setAmount(bolusValue);

        return aSyncSingleCommand(message, "Deliver Bolus " + bolusValue);
    }

    @Override
    public JSONObject getJSONStatus() {

        if (Helpers.msSince(connector.getLastContactTime()) > (60 * 60 * 1000)) {
            log("getJSONStatus not returning as data likely stale");
            return null;
        }

        final JSONObject pump = new JSONObject();
        final JSONObject battery = new JSONObject();
        final JSONObject status = new JSONObject();
        final JSONObject extended = new JSONObject();
        try {
            battery.put("percent", batteryPercent);
            status.put("status", isSuspended() ? "suspended" : "normal");
            status.put("timestamp", DateUtil.toISOString(connector.getLastContactTime()));
            extended.put("Version", BuildConfig.VERSION_NAME + "-" + BuildConfig.BUILDVERSION);
            try {
                extended.put("ActiveProfile", MainApp.getConfigBuilder().getProfileName());
            } catch (Exception e) {
            }
            TemporaryBasal tb = MainApp.getConfigBuilder().getTempBasalFromHistory(System.currentTimeMillis());
            if (tb != null) {
                extended.put("TempBasalAbsoluteRate", tb.tempBasalConvertedToAbsolute(System.currentTimeMillis()));
                extended.put("TempBasalStart", DateUtil.dateAndTimeString(tb.date));
                extended.put("TempBasalRemaining", tb.getPlannedRemainingMinutes());
            }
            ExtendedBolus eb = MainApp.getConfigBuilder().getExtendedBolusFromHistory(System.currentTimeMillis());
            if (eb != null) {
                extended.put("ExtendedBolusAbsoluteRate", eb.absoluteRate());
                extended.put("ExtendedBolusStart", DateUtil.dateAndTimeString(eb.date));
                extended.put("ExtendedBolusRemaining", eb.getPlannedRemainingMinutes());
            }
            status.put("timestamp", DateUtil.toISOString(new Date()));

            pump.put("battery", battery);
            pump.put("status", status);
            pump.put("extended", extended);
            pump.put("reservoir", reservoirInUnits);
            pump.put("clock", DateUtil.toISOString(new Date()));
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
        return pump;
    }

    @Override
    public String deviceID() {
        return "InsightPump";
    }

    @Override
    public PumpDescription getPumpDescription() {
        return pumpDescription;
    }

    @Override
    public String shortStatus(boolean veryShort) {
        String msg = gs(R.string.insightpump_shortname) + " Batt: " + batteryPercent + " Reserv: " + reservoirInUnits + " Basal: " + basalRate;
        if (LiveHistory.getStatus().length() > 0) {
            msg += LiveHistory.getStatus();
        }
        return msg;
    }

    private void processStatusResult() {
        if (statusResult != null) {
            batteryPercent = statusResult.getBatteryAmountMessage().getBatteryAmount();
            reservoirInUnits = (int) statusResult.getCartridgeAmountMessage().getCartridgeAmount();
            basalRate = roundDouble(statusResult.getCurrentBasalMessage().getCurrentBasalAmount(), 2);
            initialized = true; // basic communication test
        }
    }

    private String gs(int id) {
        return MainApp.instance().getString(id);
    }

    private boolean isPumpRunning() {
        if (statusResult == null) return true; // assume running if we have no information
        return statusResult.getPumpStatusMessage().getPumpStatus() == PumpStatus.STARTED;
    }

    List<StatusItem> getStatusItems(boolean refresh) {
        final List<StatusItem> l = new ArrayList<>();

        // Todo last contact time

        l.add(new StatusItem(gs(R.string.status_no_colon), connector.getLastStatusMessage()));
        l.add(new StatusItem(gs(R.string.changed), connector.getNiceLastStatusTime()));

        boolean pumpRunning;
        // also check time since received
        if (statusResult != null) {

            pumpRunning = isPumpRunning();
            if (pumpRunning) {
                l.add(new StatusItem(gs(R.string.pump_basebasalrate_label), getBaseBasalRateString() + "U"));
            } else {
                l.add(new StatusItem(gs(R.string.combo_warning), gs(R.string.pump_stopped_uppercase), StatusItem.Highlight.CRITICAL));
            }
        }

        final long offset_ms = Helpers.msSince(statusResultTime);
        final long offset_minutes = offset_ms / 60000;

        if (statusResult != null) {
            l.add(new StatusItem(gs(R.string.status_updated), Helpers.niceTimeScalar(Helpers.msSince(statusResultTime)) + " " + gs(R.string.ago)));
            l.add(new StatusItem(gs(R.string.pump_battery_label), batteryPercent + "%", batteryPercent < 100 ?
                    (batteryPercent < 90 ?
                            (batteryPercent < 70 ?
                                    (StatusItem.Highlight.BAD) : StatusItem.Highlight.NOTICE) : StatusItem.Highlight.NORMAL) : StatusItem.Highlight.GOOD));
            l.add(new StatusItem(gs(R.string.pump_reservoir_label), reservoirInUnits + "U"));
            try {
                if (statusResult.getCurrentTBRMessage().getPercentage() != 100) {
                    l.add(new StatusItem(gs(R.string.insight_active_tbr), statusResult.getCurrentTBRMessage().getPercentage() + "% " + gs(R.string.with) + " "
                            + Helpers.qs(statusResult.getCurrentTBRMessage().getLeftoverTime() - offset_minutes, 0)
                            + " " + gs(R.string.insight_min_left), StatusItem.Highlight.NOTICE));
                }
            } catch (NullPointerException e) {
                // currentTBRMessage may be null
            }

        }

        if (MainApp.getConfigBuilder().isTempBasalInProgress()) {
            try {
                l.add(new StatusItem(gs(R.string.pump_tempbasal_label), MainApp.getConfigBuilder().getTempBasalFromHistory(System.currentTimeMillis()).toStringFull()));
            } catch (NullPointerException e) {
                //
            }
        }

        if (statusResult != null) {
            try {
                statusActiveBolus(statusResult.getActiveBolusesMessage().getBolus1(), offset_minutes, l);
                statusActiveBolus(statusResult.getActiveBolusesMessage().getBolus2(), offset_minutes, l);
                statusActiveBolus(statusResult.getActiveBolusesMessage().getBolus3(), offset_minutes, l);
            } catch (NullPointerException e) {
                // getActiveBolusesMessage() may be null
            }
        }

        if (MainApp.getConfigBuilder().isInHistoryExtendedBoluslInProgress()) {
            try {

                l.add(new StatusItem(gs(R.string.virtualpump_extendedbolus_label), MainApp.getConfigBuilder().getExtendedBolusFromHistory(System.currentTimeMillis()).toString()));
            } catch (NullPointerException e) {
                //
            }
        }

        l.add(new StatusItem(gs(R.string.log_book), HistoryReceiver.getStatusString()));

        if (LiveHistory.getStatus().length() > 0) {
            l.add(new StatusItem(gs(R.string.insight_last_completed_action), LiveHistory.getStatus()));
        }

        final String keep_alive_status = Connector.getKeepAliveString();
        if (keep_alive_status != null) {
            l.add(new StatusItem(gs(R.string.insight_keep_alive_status), keep_alive_status));
        }

        final List<StatusItem> status_statistics = connector.getStatusStatistics();
        if (status_statistics.size() > 0) {
            l.addAll(status_statistics);
        }

        if (Helpers.ratelimit("insight-status-ui-refresh", 10)) {
            connector.tryToGetPumpStatusAgain();
        }
        connector.requestHistorySync();
        if (refresh) scheduleGUIUpdate();

        return l;
    }

    private synchronized void scheduleGUIUpdate() {
        if (!update_pending && connector.uiFresh()) {
            update_pending = true;
            Helpers.runOnUiThreadDelayed(new Runnable() {
                @Override
                public void run() {
                    updateGui();
                }
            }, 1000);
        }
    }

    private void statusActiveBolus(ActiveBolus activeBolus, long offset_mins, List<StatusItem> l) {
        if (activeBolus == null) return;
        switch (activeBolus.getBolusType()) {

            case STANDARD:
                l.add(new StatusItem(activeBolus.getBolusType() + " " + gs(R.string.bolus), activeBolus.getInitialAmount() + "U", StatusItem.Highlight.NOTICE));
                break;
            case EXTENDED:
                l.add(new StatusItem(activeBolus.getBolusType() + " " + gs(R.string.bolus), activeBolus.getInitialAmount() + "U " + gs(R.string.insight_total_with) + " "
                        + activeBolus.getLeftoverAmount() + "U " + gs(R.string.insight_remaining_over) + " " + (activeBolus.getDuration() - offset_mins) + " " + gs(R.string.insight_min), StatusItem.Highlight.NOTICE));
                break;
            case MULTIWAVE:
                l.add(new StatusItem(activeBolus.getBolusType() + " " + gs(R.string.bolus), activeBolus.getInitialAmount() + "U " + gs(R.string.insight_upfront_with) + " "
                        + activeBolus.getLeftoverAmount() + "U " + gs(R.string.insight_remaining_over) + " " + (activeBolus.getDuration() - offset_mins) + " " + gs(R.string.insight_min), StatusItem.Highlight.NOTICE));

                break;
            default:
                log("ERROR: unknown bolus type! " + activeBolus.getBolusType());
        }
    }

    // Utility

    private synchronized UUID aSyncSingleCommand(final AppLayerMessage msg, final String name) {
        // if (!isConnected()) return false;
        //if (isBusy()) return false;
        log("asyncSinglecommand called: " + name);
        final EventInsightPumpCallback event = new EventInsightPumpCallback();
        new Thread() {
            @Override
            public void run() {
                log("asyncSingleCommand thread");
                final SingleMessageTaskRunner singleMessageTaskRunner = new SingleMessageTaskRunner(connector.getServiceConnector(), msg);
                try {
                    singleMessageTaskRunner.fetch(new TaskRunner.ResultCallback() {
                        @Override
                        public void onResult(Object o) {
                            log(name + " success");
                            event.response_object = o;
                            if (o instanceof BolusMessage) {
                                event.response_id = ((BolusMessage) o).getBolusId();
                            }
                            event.success = true;
                            pushCallbackEvent(event);
                        }

                        @Override
                        public void onError(Exception e) {
                            log(name + " error");
                            event.message = e.getMessage();
                            pushCallbackEvent(event);
                        }
                    });

                } catch (Exception e) {
                    log("EXCEPTION" + e.toString());
                }
            }
        }.start();
        return event.request_uuid;
    }

    private synchronized UUID aSyncTaskRunner(final TaskRunner task, final String name) {
        // if (!isConnected()) return false;
        //if (isBusy()) return false;
        log("asyncTaskRunner called: " + name);
        final EventInsightPumpCallback event = new EventInsightPumpCallback();
        new Thread() {
            @Override
            public void run() {
                log("asyncTaskRunner thread");
                try {
                    task.fetch(new TaskRunner.ResultCallback() {
                        @Override
                        public void onResult(Object o) {
                            log(name + " success");
                            event.response_object = o;
                            event.success = true;
                            pushCallbackEvent(event);
                        }

                        @Override
                        public void onError(Exception e) {
                            log(name + " error");
                            event.message = e.getMessage();
                            pushCallbackEvent(event);
                        }
                    });

                } catch (Exception e) {
                    log("EXCEPTION" + e.toString());
                }
            }
        }.start();
        return event.request_uuid;
    }


    private PumpEnactResult pumpEnactFailure() {
        return new PumpEnactResult().success(false).enacted(false);
    }

    // Constraints

    @Override
    public boolean isLoopEnabled() {
        return true;
    }

    @Override
    public boolean isClosedModeEnabled() {
        return true;
    }

    @Override
    public boolean isAutosensModeEnabled() {
        return true;
    }

    @Override
    public boolean isAMAModeEnabled() {
        return true;
    }

    @Override
    public boolean isSMBModeEnabled() {
        return true;
    }

    @Override
    public Double applyBasalConstraints(Double absoluteRate) {
        return Math.min(absoluteRate, 25); // Maximum pump can support
    }

    @Override
    public Integer applyBasalConstraints(Integer percentRate) {
        return Math.min(percentRate, pumpDescription.maxTempPercent);
    }

    @Override
    public Double applyBolusConstraints(Double insulin) {
        // TODO we could check what the current max is set on the pump and use that information here
        // Pump can be reconfigured up to 50U max
        return Math.min(insulin, 25);
    }

    @Override
    public Integer applyCarbsConstraints(Integer carbs) {
        return carbs;
    }

    @Override
    public Double applyMaxIOBConstraints(Double maxIob) {
        return maxIob;
    }


}
