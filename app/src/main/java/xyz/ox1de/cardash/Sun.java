package xyz.ox1de.cardash;

import java.util.Calendar;
import java.util.TimeZone;

/** Sunrise / sunset from latitude/longitude (standard sunrise equation, ~1 min accuracy). Fully offline. */
final class Sun {
    private Sun() { }

    /** @return {sunriseMinuteOfDay, sunsetMinuteOfDay} in local time, or null in polar day/night. */
    static int[] times(double lat, double lon, Calendar day) {
        Calendar noon = (Calendar) day.clone();
        noon.set(Calendar.HOUR_OF_DAY, 12);
        noon.set(Calendar.MINUTE, 0);
        double jd = noon.getTimeInMillis() / 86400000.0 + 2440587.5;
        double n = Math.ceil(jd - 2451545.0 + 0.0008);
        double jStar = n - lon / 360.0;
        double m = (357.5291 + 0.98560028 * jStar) % 360.0;
        double mr = Math.toRadians(m);
        double c = 1.9148 * Math.sin(mr) + 0.02 * Math.sin(2 * mr) + 0.0003 * Math.sin(3 * mr);
        double lambda = Math.toRadians((m + c + 180.0 + 102.9372) % 360.0);
        double transit = 2451545.0 + jStar + 0.0053 * Math.sin(mr) - 0.0069 * Math.sin(2 * lambda);
        double sinD = Math.sin(lambda) * Math.sin(Math.toRadians(23.4397));
        double cosD = Math.cos(Math.asin(sinD));
        double phi = Math.toRadians(lat);
        double cosW = (Math.sin(Math.toRadians(-0.833)) - Math.sin(phi) * sinD) / (Math.cos(phi) * cosD);
        if (cosW < -1 || cosW > 1) return null;
        double w = Math.toDegrees(Math.acos(cosW)) / 360.0;
        return new int[]{localMinute(transit - w, day.getTimeZone()), localMinute(transit + w, day.getTimeZone())};
    }

    private static int localMinute(double jd, TimeZone tz) {
        long ms = Math.round((jd - 2440587.5) * 86400000.0);
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }
}
