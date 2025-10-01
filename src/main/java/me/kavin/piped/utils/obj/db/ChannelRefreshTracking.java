package me.kavin.piped.utils.obj.db;

import jakarta.persistence.*;

@Entity
@Table(name = "channel_refresh_tracking", indexes = {@Index(columnList = "channel_id", name = "channel_refresh_tracking_channel_id_idx")})
public class ChannelRefreshTracking {

    @Id
    @Column(name = "channel_id", unique = true, nullable = false, length = 24)
    private String channelId;

    @Column(name = "last_refreshed", nullable = false)
    private long lastRefreshed;

    public ChannelRefreshTracking() {
    }

    public ChannelRefreshTracking(String channelId, long lastRefreshed) {
        this.channelId = channelId;
        this.lastRefreshed = lastRefreshed;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public long getLastRefreshed() {
        return lastRefreshed;
    }

    public void setLastRefreshed(long lastRefreshed) {
        this.lastRefreshed = lastRefreshed;
    }
}
