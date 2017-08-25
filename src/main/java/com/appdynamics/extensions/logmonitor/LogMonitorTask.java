package com.appdynamics.extensions.logmonitor;

import static com.appdynamics.extensions.logmonitor.Constants.FILESIZE_METRIC_NAME;
import static com.appdynamics.extensions.logmonitor.Constants.METRIC_PATH_SEPARATOR;
import static com.appdynamics.extensions.logmonitor.Constants.SEARCH_STRING;
import static com.appdynamics.extensions.logmonitor.util.LogMonitorUtil.closeRandomAccessFile;
import static com.appdynamics.extensions.logmonitor.util.LogMonitorUtil.createPattern;
import static com.appdynamics.extensions.logmonitor.util.LogMonitorUtil.resolvePath;

import com.appdynamics.extensions.logmonitor.config.Log;
import com.appdynamics.extensions.logmonitor.exceptions.FileException;
import com.appdynamics.extensions.logmonitor.processors.FilePointer;
import com.appdynamics.extensions.logmonitor.processors.FilePointerProcessor;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.apache.log4j.Logger;
import org.bitbucket.kienerj.OptimizedRandomAccessFile;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Florencio Sarmiento
 */
public class LogMonitorTask implements Callable<LogMetrics> {

    private static final Logger LOGGER =
            Logger.getLogger(LogMonitorTask.class);

    private FilePointerProcessor filePointerProcessor;

    private Log log;

    private Map<Pattern, String> replacers;

    public LogMonitorTask(FilePointerProcessor filePointerProcessor, Log log, Map<Pattern, String> replacers) {
        this.filePointerProcessor = filePointerProcessor;
        this.log = log;
        this.replacers = replacers;
    }

    public LogMetrics call() throws Exception {
        String dirPath = resolveDirPath(log.getLogDirectory());
        LOGGER.info("Log monitor task started...");

        LogMetrics logMetrics = new LogMetrics();
        OptimizedRandomAccessFile randomAccessFile = null;

        long curFilePointer = 0;

        try {
            File file = getLogFile(dirPath);
            randomAccessFile = new OptimizedRandomAccessFile(file, "r");
            long fileSize = randomAccessFile.length();
            String dynamicLogPath = dirPath + log.getLogName();
            curFilePointer = getCurrentFilePointer(dynamicLogPath, file.getPath(), fileSize);
            List<SearchPattern> searchPatterns = createPattern(log.getSearchStrings());

            LOGGER.info(String.format("Processing log file [%s], starting from [%s]",
                    file.getPath(), curFilePointer));

            randomAccessFile.seek(curFilePointer);

            String currentLine = null;

            if (LOGGER.isDebugEnabled()) {
                for (SearchPattern searchPattern : searchPatterns) {
                    LOGGER.debug(String.format("Searching for [%s]", searchPattern.getPattern().pattern()));
                }
            }

            while ((currentLine = randomAccessFile.readLine()) != null) {
                incrementWordCountIfSearchStringMatched(searchPatterns, currentLine, logMetrics);
                curFilePointer = randomAccessFile.getFilePointer();
            }

            if (LOGGER.isDebugEnabled() && logMetrics.getMetrics().isEmpty()) {
                LOGGER.debug("No word metrics to upload, no matches found!");
            }

            logMetrics.add(getLogNamePrefix() + FILESIZE_METRIC_NAME, BigInteger.valueOf(fileSize));

            setNewFilePointer(dynamicLogPath, file.getPath(), curFilePointer);

            LOGGER.info(String.format("Sucessfully processed log file [%s]",
                    file.getPath()));

        } finally {
            closeRandomAccessFile(randomAccessFile);
        }

        return logMetrics;
    }

    private File getLogFile(String dirPath) throws FileNotFoundException {
        File directory = new File(dirPath);
        File logFile = null;

        if (directory.isDirectory()) {
            FileFilter fileFilter = new WildcardFileFilter(log.getLogName());
            File[] files = directory.listFiles(fileFilter);

            if (files != null && files.length > 0) {
                logFile = getLatestFile(files);

                if (!logFile.canRead()) {
                    throw new FileException(
                            String.format("Unable to read file [%s]", logFile.getPath()));
                }

            } else {
                throw new FileNotFoundException(
                        String.format("Unable to find any file with name [%s] in [%s]",
                                log.getLogName(), dirPath));
            }

        } else {
            throw new FileNotFoundException(
                    String.format("Directory [%s] not found. Ensure it is a directory.",
                            dirPath));
        }

        return logFile;
    }

    private String resolveDirPath(String confDirPath) {
        String resolvedPath = resolvePath(confDirPath);

        if (!resolvedPath.endsWith(File.separator)) {
            resolvedPath = resolvedPath + File.separator;
        }

        return resolvedPath;
    }

    private File getLatestFile(File[] files) {
        File latestFile = null;
        long lastModified = Long.MIN_VALUE;

        for (File file : files) {
            if (file.lastModified() > lastModified) {
                latestFile = file;
                lastModified = file.lastModified();
            }
        }

        return latestFile;
    }

    private long getCurrentFilePointer(String dynamicLogPath,
                                       String actualLogPath, long fileSize) {

        FilePointer filePointer =
                filePointerProcessor.getFilePointer(dynamicLogPath, actualLogPath);

        long currentPosition = filePointer.getLastReadPosition().get();

        if (isFilenameChanged(filePointer.getFilename(), actualLogPath) ||
                isLogRotated(fileSize, currentPosition)) {

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Filename has either changed or rotated, resetting position to 0");
            }

            currentPosition = 0;
        }

        return currentPosition;
    }

    private boolean isLogRotated(long fileSize, long startPosition) {
        return fileSize < startPosition;
    }

    private boolean isFilenameChanged(String oldFilename, String newFilename) {
        return !oldFilename.equals(newFilename);
    }

    private void incrementWordCountIfSearchStringMatched(List<SearchPattern> searchPatterns,
                                                         String stringToCheck, LogMetrics logMetrics) {
        for (SearchPattern searchPattern : searchPatterns) {
            Matcher matcher = searchPattern.getPattern().matcher(stringToCheck);
            String logMetricPrefix = getSearchStringPrefix();
            String currentKey = logMetricPrefix + searchPattern.getDisplayName() + METRIC_PATH_SEPARATOR + "Global Seed Count";
            if(!logMetrics.getMetrics().containsKey(currentKey)) {
                logMetrics.add(currentKey, BigInteger.ZERO);
            }
            while (matcher.find()) {
                BigInteger globalSeedCount = logMetrics.getMetrics().get(currentKey);
                logMetrics.add(currentKey, globalSeedCount.add(BigInteger.ONE));
                String word = matcher.group().trim();
                String replacedWord = applyReplacers(word);
                if(searchPattern.getPrintMatchedString()) {
                    if (searchPattern.getCaseSensitive()) {
                        logMetrics.add(logMetricPrefix + searchPattern.getDisplayName() + METRIC_PATH_SEPARATOR + "Matches" + METRIC_PATH_SEPARATOR + replacedWord);
                    } else {
                        logMetrics.add(logMetricPrefix + searchPattern.getDisplayName() + METRIC_PATH_SEPARATOR + "Matches" + METRIC_PATH_SEPARATOR + WordUtils.capitalizeFully(replacedWord));
                    }
                }
            }
        }
    }

    private void setNewFilePointer(String dynamicLogPath,
                                   String actualLogPath, long lastReadPosition) {
        filePointerProcessor.updateFilePointer(dynamicLogPath, actualLogPath, lastReadPosition);
    }

    private String getSearchStringPrefix() {
        return String.format("%s%s%s", getLogNamePrefix(),
                SEARCH_STRING, METRIC_PATH_SEPARATOR);
    }

    private String getLogNamePrefix() {
        String displayName = StringUtils.isBlank(log.getDisplayName()) ?
                log.getLogName() : log.getDisplayName();

        return displayName + METRIC_PATH_SEPARATOR;
    }

    private String applyReplacers(String name) {

        if (name == null || name.length() == 0 || replacers == null) {
            return name;
        }

        for (Map.Entry<Pattern, String> replacerEntry : replacers.entrySet()) {

            Pattern pattern = replacerEntry.getKey();

            Matcher matcher = pattern.matcher(name);
            name = matcher.replaceAll(replacerEntry.getValue());
        }

        return name;
    }
}
