/*******************************************************************************
 * Copyright (c) 2017 Microsoft Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Microsoft Corporation - initial API and implementation
 *******************************************************************************/

package com.microsoft.java.debug.plugin.internal;

import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;

import com.microsoft.java.debug.core.Configuration;

public final class LogUtils {
    private static final Logger logger = Logger.getLogger(Configuration.LOGGER_NAME);
    private static final Logger usageDataLogger = Logger.getLogger(Configuration.USAGE_DATA_LOGGER_NAME);


    /**
     * Initialize logger for logger level and logger handler.
     * @param level the logger level for java debugger.
     */
    public static void initialize(Level level) {
        for (Handler handle : logger.getHandlers()) {
            logger.removeHandler(handle);
        }
        for (Handler handle : usageDataLogger.getHandlers()) {
            usageDataLogger.removeHandler(handle);
        }
        logger.addHandler(new JdtLogHandler());
        logger.addHandler(new UsageDataLogHandler(Level.SEVERE));
        usageDataLogger.addHandler(new UsageDataLogHandler(Level.ALL));
        logger.setLevel(level);
    }

    public static void deinitialize() {
        for (Handler h: usageDataLogger.getHandlers()) {
            usageDataLogger.removeHandler(h);
        }
        Log.removeAllHandlers();
    }

    /**
     * Configure log level setting for java debugger.
     * @param arguments the first element of the arguments should be the String representation of level(info, fine, warning..).
     */
    public static Object configLogLevel(List<Object> arguments) {
        if (arguments != null && arguments.size() == 1 && arguments.get(0) instanceof String) {
            try {
                logger.setLevel(Level.parse((String) arguments.get(0)));
                logger.info(String.format("Set log level to : %s", arguments.get(0)));
                return logger.getLevel().toString();
            } catch (IllegalArgumentException e) {
                logger.severe(String.format("Invalid log level: %s", arguments.get(0)));
            }

        } else {
            logger.severe(String.format("Invalid parameters for configLogLevel: %s", StringUtils.join(arguments)));
        }
        return null;
    }

}
