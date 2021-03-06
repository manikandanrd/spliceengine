/*
 * Copyright 2012 - 2016 Splice Machine, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.splicemachine.concurrent.traffic;

import com.splicemachine.concurrent.Clock;

import java.util.concurrent.TimeUnit;

/**
 * Utility class for generating Traffic shapers.
 *
 * @author Scott Fines
 *         Date: 11/13/14
 */
public class TrafficShaping {

    private TrafficShaping(){}

    public static TrafficController fixedRateTrafficShaper(int maxInstantaneousPermits,
                                                       int permitsPerUnitTime,TimeUnit timeUnit){
        TokenBucket.TokenStrategy tokenStrategy = new ConstantRateTokenStrategy(permitsPerUnitTime,timeUnit);
        return new TokenBucket(maxInstantaneousPermits,tokenStrategy,new RetryBackoffWaitStrategy(1024));
    }

    public static TrafficController fixedRateTrafficShaper(int maxInstantaneousPermits,
                                                           int permitsPerUnitTime,TimeUnit timeUnit,
                                                           Clock clock){
        TokenBucket.TokenStrategy tokenStrategy = new ConstantRateTokenStrategy(permitsPerUnitTime,timeUnit);
        return new TokenBucket(maxInstantaneousPermits,tokenStrategy,new RetryBackoffWaitStrategy(1024),clock);
    }
}
