/*  Copyright (C) 2015-2017 Andreas Shimokawa, Carsten Pfeiffer, Daniele
    Gobbetti

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.miband;

import java.util.List;

import de.greenrobot.dao.query.QueryBuilder;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.MiBandActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.MiBandActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

public class MiBand2SampleProvider extends AbstractMiBandSampleProvider {
// these come from Mi1
//    public static final int TYPE_LIGHT_SLEEP = 5;
//    public static final int TYPE_ACTIVITY = -1;
//    public static final int TYPE_UNKNOWN = -1;
//    public static final int TYPE_NONWEAR = 3;
//    public static final int TYPE_CHARGING = 6;


    // observed the following values so far:
    // 00 01 02 09 0a 0b 0c 10 11

    // 0 = same activity kind as before
    // 1 = light activity walking?
    // 3 = definitely non-wear
    // 9 = probably light sleep, definitely some kind of sleep
    // 10 = ignore, except for hr (if valid)
    // 11 = probably deep sleep
    // 12 = definitely wake up
    // 17 = definitely not sleep related

    public static final int TYPE_UNSET = -1;
    public static final int TYPE_NO_CHANGE = 0;
    public static final int TYPE_ACTIVITY = 1;
    public static final int TYPE_RUNNING = 2;
    public static final int TYPE_NONWEAR = 3;
    public static final int TYPE_CHARGING = 6;
    public static final int TYPE_LIGHT_SLEEP = 9;
    public static final int TYPE_IGNORE = 10;
    public static final int TYPE_DEEP_SLEEP = 11;
    public static final int TYPE_WAKE_UP = 12;

    public MiBand2SampleProvider(GBDevice device, DaoSession session) {
        super(device, session);
    }

    @Override
    protected List<MiBandActivitySample> getGBActivitySamples(int timestamp_from, int timestamp_to, int activityType) {
        List<MiBandActivitySample> samples = super.getGBActivitySamples(timestamp_from, timestamp_to, activityType);
        postprocess(samples);
        return samples;
    }

    /**
     * "Temporary" runtime post processing of activity kinds.
     * @param samples
     */
    private void postprocess(List<MiBandActivitySample> samples) {
        if (samples.isEmpty()) {
            return;
        }

        int lastValidKind = determinePreviousValidActivityType(samples.get(0));
        for (MiBandActivitySample sample : samples) {
            int rawKind = sample.getRawKind();
            if (rawKind != TYPE_UNSET) {
                rawKind &= 0xf;
                sample.setRawKind(rawKind);
            }

            switch (rawKind) {
                case TYPE_IGNORE:
                case TYPE_NO_CHANGE:
                    if (lastValidKind != TYPE_UNSET) {
                        sample.setRawKind(lastValidKind);
                    }
                    break;
                default:
                    lastValidKind = rawKind;
                    break;
            }
        }
    }

    private int determinePreviousValidActivityType(MiBandActivitySample sample) {
        QueryBuilder<MiBandActivitySample> qb = getSampleDao().queryBuilder();
        qb.where(MiBandActivitySampleDao.Properties.DeviceId.eq(sample.getDeviceId()),
                MiBandActivitySampleDao.Properties.UserId.eq(sample.getUserId()),
                MiBandActivitySampleDao.Properties.Timestamp.lt(sample.getTimestamp()),
                MiBandActivitySampleDao.Properties.RawKind.notIn(TYPE_NO_CHANGE, TYPE_IGNORE, TYPE_UNSET, 16, 80, 96, 112)); // all I ever had that are 0 when doing &=0xf
        qb.orderDesc(MiBandActivitySampleDao.Properties.Timestamp);
        qb.limit(1);
        List<MiBandActivitySample> result = qb.build().list();
        if (result.size() > 0) {
            return result.get(0).getRawKind() & 0xf;
        }
        return TYPE_UNSET;
    }

    @Override
    public int normalizeType(int rawType) {
        switch (rawType) {
            case TYPE_DEEP_SLEEP:
                return ActivityKind.TYPE_DEEP_SLEEP;
            case TYPE_LIGHT_SLEEP:
                return ActivityKind.TYPE_LIGHT_SLEEP;
            case TYPE_ACTIVITY:
            case TYPE_RUNNING:
            case TYPE_WAKE_UP:
                return ActivityKind.TYPE_ACTIVITY;
            case TYPE_NONWEAR:
                return ActivityKind.TYPE_NOT_WORN;
            case TYPE_CHARGING:
                return ActivityKind.TYPE_NOT_WORN; //I believe it's a safe assumption
            default:
            case TYPE_UNSET: // fall through
                return ActivityKind.TYPE_UNKNOWN;
        }
    }

    @Override
    public int toRawActivityKind(int activityKind) {
        switch (activityKind) {
            case ActivityKind.TYPE_ACTIVITY:
                return TYPE_ACTIVITY;
            case ActivityKind.TYPE_DEEP_SLEEP:
                return TYPE_DEEP_SLEEP;
            case ActivityKind.TYPE_LIGHT_SLEEP:
                return TYPE_LIGHT_SLEEP;
            case ActivityKind.TYPE_NOT_WORN:
                return TYPE_NONWEAR;
            case ActivityKind.TYPE_UNKNOWN: // fall through
            default:
                return TYPE_UNSET;
        }
    }
}
