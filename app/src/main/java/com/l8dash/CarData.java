package com.l8dash;

import android.content.Context;

/**
 * Reads car values through the read-only ShellHelper (127.0.0.1), because BYD's native service
 * denies normal apps (signature-only *_GET perms) and the SDK's getInt is hidden-API blocked.
 * The helper runs as shell and only serves getInt (txn 5) / getFloat (txn 7). Values and feature
 * IDs are the CAN-FD ones verified on this car (see Leopard 8 ADB/FINDINGS.md). Missing -> null -> "—".
 */
final class CarData {

    static final class Snapshot {
        final Double[] tyrePsi = new Double[4], tyreTemp = new Double[4]; // FL, FR, RL, RR
        Double battery, fuel, evRange, fuelRange, totalRange, speed, odometer, outside, volt12;
        boolean charging;
        final boolean[] doorOpen = new boolean[4]; // FL, FR, RL, RR
        double sunroofPct;                          // 0..1 open

        boolean driving() { return speed != null && speed > 2; }
    }

    // Device types
    private static final int AC = 1000, OTA = 1032, STAT = 1014, GEARBOX = 1011,
            SPEED = 1013, TYRE = 1016, CHARGE = 1009, INSTR = 1007, BODY = 1001;
    // Feature IDs (CAN-FD, verified)
    private static final int F_SPEED = 134217760, F_BATTERY = 1246777400, F_EVRANGE = 1246765118,
            F_FUEL = 1246785600, F_FUELRANGE = 1246773304, F_ODO = 1246765072, F_VOLT12 = 1128267816,
            F_OUTSIDE = 1077936184, F_GUN = 876609586, F_SUNROOF = 1101004808; // MOON_ROOF_OPEN_PERCENT
    private static final int[] F_TYRE = {-1728052956, -1728052952, -1728052948, -1728052944}; // FL FR RL RR
    private static final int[] F_DOOR = {638582804, 638582805, 638582806, 638582807}; // FL FR RL RR

    static final double TYRE_TO_PSI = 0.145; // raw kPa -> psi
    static final double ODO_SCALE = 0.1;     // raw total-mileage is in 0.1 km units

    CarData(Context ctx) { }

    Snapshot read() {
        Snapshot s = new Snapshot();
        s.speed = f(SPEED, F_SPEED);
        s.battery = f(STAT, F_BATTERY);
        s.evRange = i(STAT, F_EVRANGE);
        s.fuel = i(STAT, F_FUEL);
        s.fuelRange = i(STAT, F_FUELRANGE);
        Double odoRaw = i(STAT, F_ODO);
        s.odometer = odoRaw == null ? null : odoRaw * ODO_SCALE; // raw is 0.1 km units
        if (s.evRange != null && s.fuelRange != null) s.totalRange = s.evRange + s.fuelRange;
        s.volt12 = f(OTA, F_VOLT12);
        s.outside = i(AC, F_OUTSIDE);
        for (int t = 0; t < 4; t++) {
            Double p = i(TYRE, F_TYRE[t]);
            s.tyrePsi[t] = p == null ? null : p * TYRE_TO_PSI;
            Double d = i(INSTR, F_DOOR[t]); s.doorOpen[t] = d != null && d.intValue() != 0;
        }
        Double sr = i(BODY, F_SUNROOF);
        s.sunroofPct = sr == null ? 0 : Math.max(0, Math.min(1, sr / 100.0));
        Double gun = i(CHARGE, F_GUN);
        s.charging = gun != null && gun.intValue() >= 2; // 0 none, 1 connected-idle, >=2 charging
        return s;
    }

    private static Double i(int dev, int feat) { return q(5, dev, feat); }
    private static Double f(int dev, int feat) { return q(7, dev, feat); }

    private static Double q(int txn, int dev, int feat) {
        try {
            String[] r = ShellHelper.query(txn, dev, feat);
            if (!"0".equals(r[0])) return null;               // denied / no data
            double v = Double.parseDouble(r[1]);
            int iv = (int) v;
            if (iv == -10011 || iv == -10013 || iv == 65535) return null; // not supported / wrong type / invalid
            return v;
        } catch (Exception e) {
            return null; // helper not running -> everything "—"
        }
    }
}
