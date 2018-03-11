package info.nightscout.androidaps.plugins.Overview.Dialogs;

import android.content.Context;
import android.os.Bundle;
import android.os.HandlerThread;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.AlertDialog;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.wdullaer.materialdatetimepicker.date.DatePickerDialog;
import com.wdullaer.materialdatetimepicker.time.RadialPickerLayout;
import com.wdullaer.materialdatetimepicker.time.TimePickerDialog;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.Date;

import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.DetailedBolusInfo;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.CareportalEvent;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.plugins.ConfigBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.Loop.LoopPlugin;
import info.nightscout.androidaps.queue.Callback;
import info.nightscout.utils.DateUtil;
import info.nightscout.utils.DecimalFormatter;
import info.nightscout.utils.NumberPicker;
import info.nightscout.utils.SP;
import info.nightscout.utils.SafeParse;
import info.nightscout.utils.ToastUtils;

public class NewCarbsDialog extends DialogFragment implements OnClickListener, DatePickerDialog.OnDateSetListener, TimePickerDialog.OnTimeSetListener {
    private static Logger log = LoggerFactory.getLogger(NewCarbsDialog.class);

    private EditText foodText;
    private NumberPicker editCarbs;

    private TextView dateButton;
    private TextView timeButton;

    private Date initialEventTime;
    private Date eventTime;

    private Button fav1Button;
    private Button fav2Button;
    private Button fav3Button;

    private static final double FAV1_DEFAULT = 5;
    private static final double FAV2_DEFAULT = 10;
    private static final double FAV3_DEFAULT = 20;
    private CheckBox suspendLoopCheckbox;
    private CheckBox startActivityTTCheckbox;
    private CheckBox startEatingSoonTTCheckbox;

    private Integer maxCarbs;

    //one shot guards
    private boolean accepted;
    private boolean okClicked;

    public NewCarbsDialog() {
        HandlerThread mHandlerThread = new HandlerThread(NewCarbsDialog.class.getSimpleName());
        mHandlerThread.start();
    }

    final private TextWatcher textWatcher = new TextWatcher() {
        @Override
        public void afterTextChanged(Editable s) {
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            validateInputs();
        }
    };

    private void validateInputs() {
        Integer carbs = SafeParse.stringToInt(editCarbs.getText());
        if (carbs > maxCarbs) {
            editCarbs.setValue(0d);
            ToastUtils.showToastInUiThread(MainApp.instance().getApplicationContext(), MainApp.gs(R.string.carbsconstraintapplied));
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.overview_newcarbs_dialog, container, false);

        view.findViewById(R.id.ok).setOnClickListener(this);
        view.findViewById(R.id.cancel).setOnClickListener(this);

        getDialog().getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        getDialog().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

        maxCarbs = MainApp.getConfigBuilder().applyCarbsConstraints(Constants.carbsOnlyForCheckLimit);

        foodText = view.findViewById(R.id.newcarb_food);

        editCarbs = view.findViewById(R.id.newcarb_carbsamount);

        editCarbs.setParams(0d, 0d, (double) maxCarbs, 1d, new DecimalFormat("0"), false, textWatcher);

        startActivityTTCheckbox = view.findViewById(R.id.newcarbs_activity_tt);
        startEatingSoonTTCheckbox = view.findViewById(R.id.carbs_eating_soon_tt);

        dateButton = view.findViewById(R.id.newcarbs_eventdate);
        timeButton = view.findViewById(R.id.newcarb_eventtime);

        initialEventTime = new Date();
        eventTime = new Date(initialEventTime.getTime());
        dateButton.setText(DateUtil.dateString(eventTime));
        timeButton.setText(DateUtil.timeString(eventTime));
        dateButton.setOnClickListener(this);
        timeButton.setOnClickListener(this);

        //To be able to select only one TT at a time
        startEatingSoonTTCheckbox.setOnClickListener(this);
        startActivityTTCheckbox.setOnClickListener(this);

// TODO prefilling carbs, maybe
// TODO maybe update suggested carbs to target TT when checked
//        APSResult lastAPSResult = ConfigBuilderPlugin.getActiveAPS().getLastAPSResult();
//        if (lastAPSResult != null && lastAPSResult instanceof DetermineBasalResultSMB && ((DetermineBasalResultSMB) lastAPSResult).carbsReq > 0) {
//            editCarbs.setValue(((DetermineBasalResultSMB) lastAPSResult).carbsReq);
//        }

        fav1Button = view.findViewById(R.id.newcarbs_plus1);
        fav1Button.setOnClickListener(this);
        fav1Button.setText("+" + SP.getString(R.string.key_carbs_button_increment_1, String.valueOf(FAV1_DEFAULT)));
        fav2Button = view.findViewById(R.id.newcarbs_plus2);
        fav2Button.setOnClickListener(this);
        fav2Button.setText("+" + SP.getString(R.string.key_carbs_button_increment_2, String.valueOf(FAV2_DEFAULT)));
        fav3Button = view.findViewById(R.id.newcarbs_plus3);
        fav3Button.setOnClickListener(this);
        fav3Button.setText("+" + SP.getString(R.string.key_carbs_button_increment_3, String.valueOf(FAV3_DEFAULT)));

        suspendLoopCheckbox = view.findViewById(R.id.newcarbs_suspend_loop);

        setCancelable(true);
        getDialog().setCanceledOnTouchOutside(false);
        return view;
    }

    @Override
    public synchronized void onClick(View view) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(eventTime);
        switch (view.getId()) {
            case R.id.ok:
                submit();
                break;
            case R.id.cancel:
                dismiss();
                break;
            case R.id.newcarbs_eventdate:
                DatePickerDialog dpd = DatePickerDialog.newInstance(
                        this,
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH)
                );
                dpd.setThemeDark(true);
                dpd.dismissOnPause(true);
                dpd.show(getActivity().getFragmentManager(), "Datepickerdialog");
                break;
            case R.id.newcarb_eventtime:
                TimePickerDialog tpd = TimePickerDialog.newInstance(
                        this,
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        DateFormat.is24HourFormat(getActivity())
                );
                tpd.setThemeDark(true);
                tpd.dismissOnPause(true);
                tpd.show(getActivity().getFragmentManager(), "Timepickerdialog");
                break;
            case R.id.newcarbs_plus1:
                editCarbs.setValue(editCarbs.getValue()
                        + SP.getDouble(R.string.key_carbs_button_increment_1, FAV1_DEFAULT));
                validateInputs();
                break;
            case R.id.newcarbs_plus2:
                editCarbs.setValue(editCarbs.getValue()
                        + SP.getDouble(R.string.key_carbs_button_increment_2, FAV2_DEFAULT));
                validateInputs();
                break;
            case R.id.newcarbs_plus3:
                editCarbs.setValue(editCarbs.getValue()
                        + SP.getDouble(R.string.key_carbs_button_increment_3, FAV3_DEFAULT));
                validateInputs();
                break;
            case R.id.newcarbs_activity_tt:
                startEatingSoonTTCheckbox.setChecked(false);
                break;
            case R.id.carbs_eating_soon_tt:
                startActivityTTCheckbox.setChecked(false);
                break;

        }
    }

    private void submit() {
        if (okClicked) {
            log.debug("guarding: ok already clicked");
            dismiss();
            return;
        }
        okClicked = true;
        try {
            final String food = StringUtils.trimToNull(foodText.getText().toString());
            final Integer carbs = SafeParse.stringToInt(editCarbs.getText());
            Integer carbsAfterConstraints = MainApp.getConfigBuilder().applyCarbsConstraints(carbs);

            String confirmMessage = "";
            if (carbs > 0)
                confirmMessage += MainApp.gs(R.string.carbs) + ": " + "<font color='" + MainApp.gc(R.color.colorCarbsButton) + "'>" + carbsAfterConstraints + "g" + "</font>";
            if (!carbsAfterConstraints.equals(carbs))
                confirmMessage += "<br/><font color='" + MainApp.gc(R.color.low) + "'>" + MainApp.gs(R.string.carbsconstraintapplied) + "</font>";
            if (suspendLoopCheckbox.isChecked()) {
                confirmMessage += "<br/>" + MainApp.gs(R.string.loop) + ": " + "<font color='" + MainApp.gc(R.color.low) + "'>" + MainApp.gs(R.string.suspendloopfor30min) + "</font>";
            }

            final Profile currentProfile = MainApp.getConfigBuilder().getProfile();
            if (currentProfile == null)
                return;

            int activityTTDuration = SP.getInt(R.string.key_activity_duration, Constants.defaultActivityTTDuration);
            activityTTDuration = activityTTDuration > 0 ? activityTTDuration : Constants.defaultActivityTTDuration;
            double activityTT = SP.getDouble(R.string.key_activity_target, currentProfile.getUnits().equals(Constants.MMOL) ? Constants.defaultActivityTTmmol : Constants.defaultActivityTTmgdl);
            activityTT = activityTT > 0 ? activityTT : currentProfile.getUnits().equals(Constants.MMOL) ? Constants.defaultActivityTTmmol : Constants.defaultActivityTTmgdl;

            int eatingSoonTTDuration = SP.getInt(R.string.key_eatingsoon_duration, Constants.defaultEatingSoonTTDuration);
            eatingSoonTTDuration = eatingSoonTTDuration > 0 ? eatingSoonTTDuration : Constants.defaultEatingSoonTTDuration;
            double eatingSoonTT = SP.getDouble(R.string.key_eatingsoon_target, currentProfile.getUnits().equals(Constants.MMOL) ? Constants.defaultEatingSoonTTmmol : Constants.defaultEatingSoonTTmgdl);
            eatingSoonTT = eatingSoonTT > 0 ? eatingSoonTT : currentProfile.getUnits().equals(Constants.MMOL) ? Constants.defaultEatingSoonTTmmol : Constants.defaultEatingSoonTTmgdl;

            if (startActivityTTCheckbox.isChecked()) {
                if (currentProfile.getUnits().equals(Constants.MMOL)) {
                    confirmMessage += "<br/>" + MainApp.gs(R.string.temptargetshort) + ": " + "<font color='" + MainApp.gc(R.color.high) + "'>" + DecimalFormatter.to1Decimal(activityTT) + " mmol/l (" + ((int) activityTTDuration) + " min)</font>";
                } else
                    confirmMessage += "<br/>" + MainApp.gs(R.string.temptargetshort) + ": " + "<font color='" + MainApp.gc(R.color.high) + "'>" + DecimalFormatter.to0Decimal(activityTT) + " mg/dl (" + ((int) activityTTDuration) + " min)</font>";

            }
            if (startEatingSoonTTCheckbox.isChecked() && !startActivityTTCheckbox.isChecked()) {
                if (currentProfile.getUnits().equals(Constants.MMOL)) {
                    confirmMessage += "<br/>" + MainApp.gs(R.string.temptargetshort) + ": " + "<font color='" + MainApp.gc(R.color.high) + "'>" + DecimalFormatter.to1Decimal(eatingSoonTT) + " mmol/l (" + eatingSoonTTDuration + " min)</font>";
                } else
                    confirmMessage += "<br/>" + MainApp.gs(R.string.temptargetshort) + ": " + "<font color='" + MainApp.gc(R.color.high) + "'>" + DecimalFormatter.to0Decimal(eatingSoonTT) + " mg/dl (" + eatingSoonTTDuration + " min)</font>";

            }
            final double finalActivityTT = activityTT;
            final double finalEatigSoonTT = eatingSoonTT;
            final int finalActivityTTDuration = activityTTDuration;
            final int finalEatingSoonTTDuration = eatingSoonTTDuration;

            if (StringUtils.isNoneEmpty(food)) {
                confirmMessage += "<br/>" + "Food: " + food;
            }

            if (!initialEventTime.equals(eventTime)) {
                confirmMessage += "<br/> Time: " + DateUtil.dateAndTimeString(eventTime);
            }
            if (confirmMessage.length() > 0) {

                final int finalCarbsAfterConstraints = carbsAfterConstraints;

                final Context context = getContext();
                final AlertDialog.Builder builder = new AlertDialog.Builder(context);

                builder.setTitle(MainApp.gs(R.string.confirmation));
                if (confirmMessage.startsWith("<br/>"))
                    confirmMessage = confirmMessage.substring("<br/>".length());

                builder.setMessage(Html.fromHtml(confirmMessage));
                builder.setPositiveButton(MainApp.gs(R.string.ok), (dialog, id) -> {
                    synchronized (builder) {
                        if (accepted) {
                            log.debug("guarding: already accepted");
                            return;
                        }
                        accepted = true;

                        if (suspendLoopCheckbox.isChecked()) {
                            final LoopPlugin activeloop = ConfigBuilderPlugin.getActiveLoop();
                            activeloop.suspendTo(System.currentTimeMillis() + 30L * 60 * 1000);
                            ConfigBuilderPlugin.getCommandQueue().cancelTempBasal(true, new Callback() {
                                @Override
                                public void run() {
                                    if (!result.success) {
                                        ToastUtils.showToastInUiThread(MainApp.instance().getApplicationContext(), MainApp.gs(R.string.tempbasaldeliveryerror));
                                    }
                                }
                            });
                        }

                        if (startActivityTTCheckbox.isChecked()) {
                            TempTarget tempTarget = new TempTarget();
                            tempTarget.date = System.currentTimeMillis();
                            tempTarget.durationInMinutes = finalActivityTTDuration;
                            tempTarget.reason = MainApp.gs(R.string.activity);
                            tempTarget.source = Source.USER;
                            tempTarget.low = Profile.toMgdl(finalActivityTT, currentProfile.getUnits());
                            tempTarget.high = Profile.toMgdl(finalActivityTT, currentProfile.getUnits());
                            MainApp.getDbHelper().createOrUpdate(tempTarget);
                        } else if (startEatingSoonTTCheckbox.isChecked()) {
                            TempTarget tempTarget = new TempTarget();
                            tempTarget.date = System.currentTimeMillis();
                            tempTarget.durationInMinutes = finalEatingSoonTTDuration;
                            tempTarget.reason = MainApp.gs(R.string.eatingsoon);
                            tempTarget.source = Source.USER;
                            tempTarget.low = Profile.toMgdl(finalEatigSoonTT, currentProfile.getUnits());
                            tempTarget.high = Profile.toMgdl(finalEatigSoonTT, currentProfile.getUnits());
                            MainApp.getDbHelper().createOrUpdate(tempTarget);
                        }

                        if (finalCarbsAfterConstraints > 0 || food != null) {
                            DetailedBolusInfo detailedBolusInfo = new DetailedBolusInfo();
                            detailedBolusInfo.date = eventTime.getTime();
                            detailedBolusInfo.eventType = CareportalEvent.CARBCORRECTION;
                            detailedBolusInfo.carbs = finalCarbsAfterConstraints;
//                        detailedBolusInfo.food = food;
                            detailedBolusInfo.context = context;
                            detailedBolusInfo.source = Source.USER;
                            MainApp.getConfigBuilder().addToHistoryTreatment(detailedBolusInfo);
                        }
                    }
                });
                builder.setNegativeButton(MainApp.gs(R.string.cancel), null);
                builder.show();
                dismiss();
            } else
                dismiss();
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }

    }

    @Override
    public void onDateSet(DatePickerDialog view, int year, int monthOfYear, int dayOfMonth) {
        eventTime.setYear(year - 1900);
        eventTime.setMonth(monthOfYear);
        eventTime.setDate(dayOfMonth);
        dateButton.setText(DateUtil.dateString(eventTime));
    }

    @Override
    public void onTimeSet(RadialPickerLayout view, int hourOfDay, int minute, int second) {
        eventTime.setHours(hourOfDay);
        eventTime.setMinutes(minute);
        eventTime.setSeconds(second);
        timeButton.setText(DateUtil.timeString(eventTime));
    }
}
