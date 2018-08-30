package info.nightscout.androidaps.plugins.PumpDanaRS.comm;


import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import info.AAPSMocker;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.PumpDanaR.DanaRPump;
import info.nightscout.utils.SP;

import static org.junit.Assert.assertEquals;

/**
 * Created by Rumen on 06.08.2018.
 */

@RunWith(PowerMockRunner.class)
@PrepareForTest({MainApp.class, SP.class, L.class})
public class DanaRS_Packet_Bolus_Get_CIR_CF_ArrayTest {

    @Test
    public void runTest() {
        AAPSMocker.mockMainApp();
        AAPSMocker.mockApplicationContext();
        AAPSMocker.mockSP();
        AAPSMocker.mockL();
        DanaRS_Packet_Bolus_Get_CIR_CF_Array packet = new DanaRS_Packet_Bolus_Get_CIR_CF_Array();

        // test params
        byte[] testparams = packet.getRequestParams();
        assertEquals(null, packet.getRequestParams());


        // test message decoding
        packet.handleMessage(createArray(34, (byte) 0));
        // are pump units MG/DL ???
        DanaRPump testPump = DanaRPump.getInstance();
        assertEquals(DanaRPump.UNITS_MGDL, testPump.units);
        assertEquals(false, packet.failed);
        packet.handleMessage(createArray(34, (byte) 3));
        assertEquals(true, packet.failed);

        assertEquals("BOLUS__GET_CIR_CF_ARRAY", packet.getFriendlyName());
    }

    byte[] createArray(int length, byte fillWith){
        byte[] ret = new byte[length];
        for(int i = 0; i<length; i++){
            ret[i] = fillWith;
        }
        return ret;
    }

    double[] createArray(int length, double fillWith){
        double[] ret = new double[length];
        for(int i = 0; i<length; i++){
            ret[i] = fillWith;
        }
        return ret;
    }
}
