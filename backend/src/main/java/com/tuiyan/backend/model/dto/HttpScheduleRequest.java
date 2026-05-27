package com.tuiyan.backend.model.dto;

/** 启停定时拉取：enabled + intervalSec（≥ 60）。 */
public class HttpScheduleRequest {
    private boolean enabled;
    private Integer intervalSec;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Integer getIntervalSec() { return intervalSec; }
    public void setIntervalSec(Integer intervalSec) { this.intervalSec = intervalSec; }
}
