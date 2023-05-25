package io.piano.analytics.avinsights;

import android.text.TextUtils;
import android.util.SparseIntArray;

import androidx.annotation.Nullable;
import io.piano.analytics.PianoAnalytics;
import io.piano.analytics.Event;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Media {

    private static final int MIN_HEARTBEAT_DURATION = 5;
    private static final int MIN_BUFFER_HEARTBEAT_DURATION = 1;
    private static final String AV_POSITION_FIELD = "av_position";
    private static final String AV_PREVIOUS_POSITION_FIELD = "av_previous_position";

    private ScheduledExecutorService heartbeatExecutor;
    private final SparseIntArray heartbeatDurations;
    private final SparseIntArray bufferHeartbeatDurations;
    private final AVRunnable heartbeatRunnable;
    private final AVRunnable bufferHeartbeatRunnable;
    private final AVRunnable rebufferHeartbeatRunnable;

    private String sessionId;
    private String previousEvent = "";
    private int previousHeartbeatDelay = 0;
    private int previousBufferHeartbeatDelay = 0;
    private int previousCursorPositionMillis = 0;
    private int currentCursorPositionMillis = 0;
    private long eventDurationMillis = 0;
    private int sessionDurationMillis = 0;
    private long startSessionTimeMillis = 0;
    private long bufferTimeMillis = 0;
    private boolean isPlaying = false;
    private boolean isPlaybackActivated = false;
    private double playbackSpeed = 1;

    private boolean autoHeartbeat;
    private boolean autoBufferHeartbeat;
    private final PianoAnalytics pianoAnalytics;

    @Nullable
    private Map<String, Object> extraProps;

    /// region PUBLIC SECTION

    public Media(PianoAnalytics pianoAnalytics) {
        this.pianoAnalytics = pianoAnalytics;
        heartbeatDurations = new SparseIntArray();
        bufferHeartbeatDurations = new SparseIntArray();

        heartbeatRunnable = new HeartbeatRunnable(this);
        bufferHeartbeatRunnable = new BufferHeartbeatRunnable(this);
        rebufferHeartbeatRunnable = new RebufferHeartbeatRunnable(this);
        this.sessionId = UUID.randomUUID().toString();
    }

    public Media(PianoAnalytics pianoAnalytics, String sessionId) {
        this(pianoAnalytics);
        if (!TextUtils.isEmpty(sessionId)) {
            this.sessionId = sessionId;
        }
    }

    /***
     * Get session
     * @return String
     */
    public synchronized String getSessionId() {
        return sessionId;
    }

    public void track(String event, Map<String, Object> options, Map<String, Object> extraProps) {
        if (options == null) {
            options = new HashMap<>();
        }

        switch (event) {
            case "av.heartbeat":
                heartbeat(parseInt(options.get(AV_POSITION_FIELD)), extraProps);
                break;
            case "av.buffer.heartbeat":
                bufferHeartbeat(extraProps);
                break;
            case "av.rebuffer.heartbeat":
                rebufferHeartbeat(extraProps);
                break;
            case "av.play":
                play(parseInt(options.get(AV_POSITION_FIELD)), extraProps);
                break;
            case "av.buffer.start":
                bufferStart(parseInt(options.get(AV_POSITION_FIELD)), extraProps);
                break;
            case "av.start":
                playbackStart(parseInt(options.get(AV_POSITION_FIELD)), extraProps);
                break;
            case "av.resume":
                playbackResumed(parseInt(options.get(AV_POSITION_FIELD)), extraProps);
                break;
            case "av.pause":
                playbackPaused(parseInt(options.get(AV_POSITION_FIELD)), extraProps);
                break;
            case "av.stop":
                playbackStopped(parseInt(options.get(AV_POSITION_FIELD)), extraProps);
                break;
            case "av.backward":
                seekBackward(parseInt(options.get(AV_PREVIOUS_POSITION_FIELD)), parseInt(options.get(AV_POSITION_FIELD)), extraProps);
                break;
            case "av.forward":
                seekForward(parseInt(options.get(AV_PREVIOUS_POSITION_FIELD)), parseInt(options.get(AV_POSITION_FIELD)), extraProps);
                break;
            case "av.seek.start":
                seekStart(parseInt(options.get(AV_PREVIOUS_POSITION_FIELD)), extraProps);
                break;
            case "av.error":
                error(parseString(options.get("av_player_error")), extraProps);
                break;
            default:
                sendEvents(createEvent(event, false, extraProps));
        }
    }

    /***
     * Set a new playback speed and update session context
     * @param playbackSpeed double
     */
    public synchronized void setPlaybackSpeed(double playbackSpeed) {
        if (this.playbackSpeed == playbackSpeed && playbackSpeed <= 0) {
            return;
        }

        stopHeartbeatService();

        if (!isPlaying) {
            this.playbackSpeed = playbackSpeed;
            return;
        }

        heartbeat(-1, null);

        if (autoHeartbeat) {
            this.previousHeartbeatDelay = updateHeartbeatRunnable(this.previousHeartbeatDelay, startSessionTimeMillis, MIN_HEARTBEAT_DURATION, heartbeatDurations, heartbeatRunnable);
        }
        this.playbackSpeed = playbackSpeed;
    }

    /**
     * Sets a map of extraProps on the media object and returns it.
     * @param extraProps The extra props (e.g. av_content properties)
     * @return The previous media object enriched with the new props
     */
    public Media setExtraProps(@Nullable Map<String, Object> extraProps) {
        this.extraProps = extraProps;
        return this;
    }

    /**
     * Returns the currently set extraProps. Note: Package-private since only AVRunnable needs it at the moment.
     * @return the extraProps
     */
    @Nullable
    Map<String, Object> getExtraProps() {
        return extraProps;
    }

    /***
     * Generate heartbeat event.
     */
    public void heartbeat(int cursorPosition, Map<String, Object> extraProps) {
        processHeartbeat(cursorPosition, false, extraProps);
    }

    /***
     * Generate heartbeat event during buffering.
     */
    public void bufferHeartbeat(Map<String, Object> extraProps) {
        processBufferHeartbeat(false, extraProps);
    }

    /***
     * Generate heartbeat event during rebuffering.
     */
    public void rebufferHeartbeat(Map<String, Object> extraProps) {
        processRebufferHeartbeat(false, extraProps);
    }

    /***
     * Generate play event (play attempt).
     * @param cursorPosition Cursor position (milliseconds)
     */
    public synchronized void play(int cursorPosition, Map<String, Object> extraProps) {
        startSessionTimeMillis = startSessionTimeMillis == 0 ? currentTimeMillis() : startSessionTimeMillis;

        eventDurationMillis = 0;

        cursorPosition = Math.max(cursorPosition, 0);
        previousCursorPositionMillis = cursorPosition;
        currentCursorPositionMillis = cursorPosition;

        bufferTimeMillis = 0;
        isPlaying = false;
        isPlaybackActivated = false;

        stopHeartbeatService();

        sendEvents(createEvent("av.play", true, extraProps));
    }

    /***
     * Player buffering start to initiate the launch of the media.
     * @param cursorPosition Cursor position (milliseconds)
     */
    public synchronized void bufferStart(int cursorPosition, Map<String, Object> extraProps) {
        startSessionTimeMillis = startSessionTimeMillis == 0 ? currentTimeMillis() : startSessionTimeMillis;

        updateDuration();
        previousCursorPositionMillis = currentCursorPositionMillis;
        currentCursorPositionMillis = Math.max(cursorPosition, 0);

        stopHeartbeatService();

        if (isPlaybackActivated) {
            if (autoBufferHeartbeat) {
                bufferTimeMillis = bufferTimeMillis == 0 ? currentTimeMillis() : bufferTimeMillis;
                this.previousBufferHeartbeatDelay = updateHeartbeatRunnable(this.previousBufferHeartbeatDelay, bufferTimeMillis, MIN_BUFFER_HEARTBEAT_DURATION, bufferHeartbeatDurations, rebufferHeartbeatRunnable);
            }
            sendEvents(createEvent("av.rebuffer.start", true, extraProps));
        } else {
            if (autoBufferHeartbeat) {
                bufferTimeMillis = bufferTimeMillis == 0 ? currentTimeMillis() : bufferTimeMillis;
                this.previousBufferHeartbeatDelay = updateHeartbeatRunnable(this.previousBufferHeartbeatDelay, bufferTimeMillis, MIN_BUFFER_HEARTBEAT_DURATION, bufferHeartbeatDurations, bufferHeartbeatRunnable);
            }
            sendEvents(createEvent("av.buffer.start", true, extraProps));
        }
    }

    /***
     * Media playback start (first frame of the media).
     * @param cursorPosition Cursor position (milliseconds)
     */
    public synchronized void playbackStart(int cursorPosition, Map<String, Object> extraProps) {
        startSessionTimeMillis = startSessionTimeMillis == 0 ? currentTimeMillis() : startSessionTimeMillis;

        updateDuration();

        cursorPosition = Math.max(cursorPosition, 0);
        previousCursorPositionMillis = cursorPosition;
        currentCursorPositionMillis = cursorPosition;
        bufferTimeMillis = 0;
        isPlaying = true;
        isPlaybackActivated = true;

        stopHeartbeatService();
        if (autoHeartbeat) {
            this.previousHeartbeatDelay = updateHeartbeatRunnable(this.previousHeartbeatDelay, startSessionTimeMillis, MIN_HEARTBEAT_DURATION, heartbeatDurations, heartbeatRunnable);
        }

        sendEvents(createEvent("av.start", true, extraProps));
    }

    /***
     * Media playback restarted manually after a pause.
     * @param cursorPosition Cursor position (milliseconds)
     */
    public synchronized void playbackResumed(int cursorPosition, Map<String, Object> extraProps) {
        startSessionTimeMillis = startSessionTimeMillis == 0 ? currentTimeMillis() : startSessionTimeMillis;

        updateDuration();

        previousCursorPositionMillis = currentCursorPositionMillis;
        currentCursorPositionMillis = Math.max(cursorPosition, 0);
        bufferTimeMillis = 0;
        isPlaying = true;
        isPlaybackActivated = true;

        stopHeartbeatService();
        if (autoHeartbeat) {
            this.previousHeartbeatDelay = updateHeartbeatRunnable(this.previousHeartbeatDelay, startSessionTimeMillis, MIN_HEARTBEAT_DURATION, heartbeatDurations, heartbeatRunnable);
        }

        sendEvents(createEvent("av.resume", true, extraProps));
    }

    /***
     * Media playback paused.
     * @param cursorPosition Cursor position (milliseconds)
     */
    public synchronized void playbackPaused(int cursorPosition, Map<String, Object> extraProps) {
        startSessionTimeMillis = startSessionTimeMillis == 0 ? currentTimeMillis() : startSessionTimeMillis;

        updateDuration();

        previousCursorPositionMillis = currentCursorPositionMillis;
        currentCursorPositionMillis = Math.max(cursorPosition, 0);
        bufferTimeMillis = 0;
        isPlaying = false;
        isPlaybackActivated = true;

        stopHeartbeatService();

        sendEvents(createEvent("av.pause", true, extraProps));
    }

    /***
     * Media playback stopped.
     * @param cursorPosition Cursor position (milliseconds)
     */
    public synchronized void playbackStopped(int cursorPosition, Map<String, Object> extraProps) {
        startSessionTimeMillis = startSessionTimeMillis == 0 ? currentTimeMillis() : startSessionTimeMillis;

        updateDuration();

        previousCursorPositionMillis = currentCursorPositionMillis;
        currentCursorPositionMillis = Math.max(cursorPosition, 0);

        bufferTimeMillis = 0;
        isPlaying = false;
        isPlaybackActivated = false;

        stopHeartbeatService();

        startSessionTimeMillis = 0;
        sessionDurationMillis = 0;
        bufferTimeMillis = 0;
        previousHeartbeatDelay = 0;
        previousBufferHeartbeatDelay = 0;

        sendEvents(createEvent("av.stop", true, extraProps));

        resetState();
    }

    /***
     * Measuring seek event.
     * @param oldCursorPosition Starting position (milliseconds)
     * @param newCursorPosition Ending position (milliseconds)
     */
    public void seek(int oldCursorPosition, int newCursorPosition, Map<String, Object> extraProps) {
        if (oldCursorPosition > newCursorPosition) {
            seekBackward(oldCursorPosition, newCursorPosition, extraProps);
        } else {
            seekForward(oldCursorPosition, newCursorPosition, extraProps);
        }
    }

    /***
     * Measuring seek backward.
     * @param oldCursorPosition Starting position (milliseconds)
     * @param newCursorPosition Ending position (milliseconds)
     */
    public void seekBackward(int oldCursorPosition, int newCursorPosition, Map<String, Object> extraProps) {
        processSeek("backward", oldCursorPosition, newCursorPosition, extraProps);
    }

    /***
     * Measuring seek forward.
     * @param oldCursorPosition Starting position (milliseconds)
     * @param newCursorPosition Ending position (milliseconds)
     */
    public void seekForward(int oldCursorPosition, int newCursorPosition, Map<String, Object> extraProps) {
        processSeek("forward", oldCursorPosition, newCursorPosition, extraProps);
    }

    /***
     * Measuring seek start.
     * @param oldCursorPosition Old Cursor position (milliseconds)
     */
    public void seekStart(int oldCursorPosition, Map<String, Object> extraProps) {
        sendEvents(createSeekStart(oldCursorPosition, extraProps));
    }

    /***
     * Measuring media click (especially for ads).
     */
    public void adClick(Map<String, Object> extraProps) {
        sendEvents(createEvent("av.ad.click", false, extraProps));
    }

    /***
     * Measuring media skip (especially for ads).
     */
    public void adSkip(Map<String, Object> extraProps) {
        sendEvents(createEvent("av.ad.skip", false, extraProps));
    }

    /***
     * Error measurement preventing reading from continuing.
     */
    public void error(String message, Map<String, Object> extraProps) {
        if (extraProps == null) {
            extraProps = new HashMap<>();
        }
        extraProps.put("av_player_error", message);
        sendEvents(createEvent("av.error", false, extraProps));
    }

    /***
     * Measuring reco or Ad display.
     */
    public void display(Map<String, Object> extraProps) {
        sendEvents(createEvent("av.display", false, extraProps));
    }

    /***
     * Measuring close action.
     */
    public void close(Map<String, Object> extraProps) {
        sendEvents(createEvent("av.close", false, extraProps));
    }

    /***
     * Measurement of a volume change action.
     */
    public void volume(Map<String, Object> extraProps) {
        sendEvents(createEvent("av.volume", false, extraProps));
    }

    /***
     * Measurement of activated subtitles.
     */
    public void subtitleOn(Map<String, Object> extraProps) {
        sendEvents(createEvent("av.subtitle.on", false, extraProps));
    }

    /***
     * Measurement of deactivated subtitles.
     */
    public void subtitleOff(Map<String, Object> extraProps) {
        sendEvents(createEvent("av.subtitle.off", false, extraProps));
    }

    /***
     * Measuring a full-screen display.
     */
    public void fullscreenOn(Map<String, Object> extraProps) {
        sendEvents(createEvent("av.fullscreen.on", false, extraProps));
    }

    /***
     * Measuring a full screen deactivation.
     */
    public void fullscreenOff(Map<String, Object> extraProps) {
        sendEvents(createEvent("av.fullscreen.off", false, extraProps));
    }

    /***
     * Measurement of a quality change action.
     */
    public void quality(Map<String, Object> extraProps) {
        sendEvents(createEvent("av.quality", false, extraProps));
    }

    /***
     * Measurement of a speed change action.
     */
    public void speed(Map<String, Object> extraProps) {
        sendEvents(createEvent("av.speed", false, extraProps));
    }

    /***
     * Measurement of a sharing action.
     */
    public void share(Map<String, Object> extraProps) {
        sendEvents(createEvent("av.share", false, extraProps));
    }

    /***
     * Set heartbeat value
     * @param heartbeat SparseIntArray
     * @return current Media instance
     */
    public Media setHeartbeat(SparseIntArray heartbeat) {
        if (heartbeat == null) {
            return this;
        }
        int size = heartbeat.size();
        if (size == 0) {
            return this;
        }
        autoHeartbeat = true;
        heartbeatDurations.clear();
        for (int i = 0; i < size; i++) {
            heartbeatDurations.put(heartbeat.keyAt(i), Math.max(heartbeat.valueAt(i), MIN_HEARTBEAT_DURATION));
        }
        if (heartbeatDurations.indexOfKey(0) < 0) {
            heartbeatDurations.put(0, MIN_HEARTBEAT_DURATION);
        }
        return this;
    }

    /***
     * Set buffer heartbeat value
     * @param bufferHeartbeat SparseIntArray
     * @return current Media instance
     */
    public Media setBufferHeartbeat(SparseIntArray bufferHeartbeat) {
        if (bufferHeartbeat == null) {
            return this;
        }
        int size = bufferHeartbeat.size();
        if (size == 0) {
            return this;
        }
        autoBufferHeartbeat = true;
        bufferHeartbeatDurations.clear();
        for (int i = 0; i < size; i++) {
            bufferHeartbeatDurations.put(bufferHeartbeat.keyAt(i), Math.max(bufferHeartbeat.valueAt(i), MIN_BUFFER_HEARTBEAT_DURATION));
        }
        if (bufferHeartbeatDurations.indexOfKey(0) < 0) {
            bufferHeartbeatDurations.put(0, MIN_BUFFER_HEARTBEAT_DURATION);
        }
        return this;
    }

    /// endregion

    /// region Private methods

    private int updateHeartbeatRunnable(int previousHeartbeatDelay, long startTimerMillis, int MIN_HEARTBEAT_DURATION, SparseIntArray heartbeatDurations, AVRunnable heartbeatRunnable) {
        int minutesDelay = (int) ((currentTimeMillis() - startTimerMillis) / 60_000);
        int heartbeatDelay = Math.max(heartbeatDurations.get(minutesDelay, previousHeartbeatDelay), MIN_HEARTBEAT_DURATION);
        heartbeatExecutor.schedule(heartbeatRunnable, heartbeatDelay, TimeUnit.SECONDS);
        return heartbeatDelay;
    }

    private synchronized Event createSeekStart(int oldCursorPosition, Map<String, Object> extraProps) {
        previousCursorPositionMillis = currentCursorPositionMillis;
        currentCursorPositionMillis = Math.max(oldCursorPosition, 0);

        if (isPlaying) {
            updateDuration();
        } else {
            eventDurationMillis = 0;
        }
        return createEvent("av.seek.start", true, extraProps);
    }

    private synchronized void processSeek(String seekDirection, int oldCursorPosition, int newCursorPosition, Map<String, Object> extraProps) {
        Event seekStart = createSeekStart(oldCursorPosition, extraProps);

        eventDurationMillis = 0;
        previousCursorPositionMillis = Math.max(oldCursorPosition, 0);
        currentCursorPositionMillis = Math.max(newCursorPosition, 0);

        sendEvents(seekStart, createEvent("av." + seekDirection, true, extraProps));
    }

    private synchronized Event createEvent(String name, boolean withOptions, Map<String, Object> extraProps) {
        Map<String, Object> props = new HashMap<>();
        if (withOptions) {
            props.put(AV_PREVIOUS_POSITION_FIELD, this.previousCursorPositionMillis);
            props.put(AV_POSITION_FIELD, this.currentCursorPositionMillis);
            props.put("av_duration", this.eventDurationMillis);
            props.put("av_previous_event", this.previousEvent);
            this.previousEvent = name;
        }
        props.put("av_session_id", sessionId);

        if (extraProps != null) {
            props.putAll(new HashMap<>(extraProps));
        }
        return new Event(name, props);
    }

    private void sendEvents(Event... events) {
        pianoAnalytics.sendEvents(Arrays.asList(events), null);
    }

    private void updateDuration() {
        eventDurationMillis = currentTimeMillis() - startSessionTimeMillis - sessionDurationMillis;
        sessionDurationMillis += eventDurationMillis;
    }

    private void stopHeartbeatService() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            heartbeatExecutor.shutdownNow();
        }
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    private void resetState() {
        sessionId = UUID.randomUUID().toString();
        previousEvent = "";
        previousCursorPositionMillis = 0;
        currentCursorPositionMillis = 0;
        eventDurationMillis = 0;
    }

    private int parseInt(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Integer) {
            return (int) o;
        }
        if (o instanceof Double) {
            return ((Double) o).intValue();
        }
        try {
            return Double.valueOf(parseString(o)).intValue();
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String parseString(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof String) {
            return (String) o;
        }
        return String.valueOf(o);
    }

    private long currentTimeMillis() {
        long timeMillis;
        int year;
        int retry = 3;
        do {
            retry--;
            timeMillis = System.currentTimeMillis();

            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(timeMillis);
            year = cal.get(Calendar.YEAR);
            if (year < 2000) {
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } while (year < 2000 && retry > 0);

        return timeMillis;
    }

    /// endregion

    /// region Package methods

    synchronized void processHeartbeat(int cursorPosition, boolean fromAuto, Map<String, Object> extraProps) {
        startSessionTimeMillis = startSessionTimeMillis == 0 ? currentTimeMillis() : startSessionTimeMillis;

        updateDuration();

        previousCursorPositionMillis = currentCursorPositionMillis;
        if (cursorPosition >= 0) {
            currentCursorPositionMillis = cursorPosition;
        } else {
            currentCursorPositionMillis += (eventDurationMillis * playbackSpeed);
        }

        if (fromAuto) {
            this.previousHeartbeatDelay = updateHeartbeatRunnable(this.previousHeartbeatDelay, startSessionTimeMillis, MIN_HEARTBEAT_DURATION, heartbeatDurations, heartbeatRunnable);
        }

        sendEvents(createEvent("av.heartbeat", true, extraProps));
    }

    synchronized void processBufferHeartbeat(boolean fromAuto, Map<String, Object> extraProps) {
        startSessionTimeMillis = startSessionTimeMillis == 0 ? currentTimeMillis() : startSessionTimeMillis;

        updateDuration();

        if (fromAuto) {
            bufferTimeMillis = bufferTimeMillis == 0 ? currentTimeMillis() : bufferTimeMillis;
            this.previousBufferHeartbeatDelay = updateHeartbeatRunnable(this.previousBufferHeartbeatDelay, bufferTimeMillis, MIN_BUFFER_HEARTBEAT_DURATION, bufferHeartbeatDurations, bufferHeartbeatRunnable);
        }
        sendEvents(createEvent("av.buffer.heartbeat", true, extraProps));
    }

    synchronized void processRebufferHeartbeat(boolean fromAuto, Map<String, Object> extraProps) {
        startSessionTimeMillis = startSessionTimeMillis == 0 ? currentTimeMillis() : startSessionTimeMillis;

        updateDuration();

        previousCursorPositionMillis = currentCursorPositionMillis;

        if (fromAuto) {
            bufferTimeMillis = bufferTimeMillis == 0 ? currentTimeMillis() : bufferTimeMillis;
            this.previousBufferHeartbeatDelay = updateHeartbeatRunnable(this.previousBufferHeartbeatDelay, bufferTimeMillis, MIN_BUFFER_HEARTBEAT_DURATION, bufferHeartbeatDurations, rebufferHeartbeatRunnable);
        }

        sendEvents(createEvent("av.rebuffer.heartbeat", true, extraProps));
    }

    /// endregion
}
