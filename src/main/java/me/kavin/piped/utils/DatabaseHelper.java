package me.kavin.piped.utils;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import me.kavin.piped.consts.Constants;
import me.kavin.piped.utils.obj.db.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hibernate.SharedSessionContract;
import org.hibernate.StatelessSession;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static me.kavin.piped.consts.Constants.YOUTUBE_SERVICE;

public class DatabaseHelper {

    // Circuit breaker for YouTube API calls
    private static final AtomicInteger consecutiveRefreshFailures = new AtomicInteger(0);
    private static final int MAX_FAILURES_BEFORE_CIRCUIT_BREAK = 10;
    private static final int CIRCUIT_BREAKER_RESET_MINUTES = 5; // Configurable cooldown period
    private static volatile long circuitBreakerResetTime = 0;

    public static User getUserFromSession(String session) {
        try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
            return getUserFromSession(session, s);
        }
    }

    public static User getUserFromSession(String session, SharedSessionContract s) {
        CriteriaBuilder cb = s.getCriteriaBuilder();
        CriteriaQuery<User> cr = cb.createQuery(User.class);
        Root<User> root = cr.from(User.class);
        cr.select(root).where(cb.equal(root.get("sessionId"), session));

        return s.createQuery(cr).uniqueResult();
    }

    public static User getUserFromSessionWithSubscribed(String session) {
        try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
            CriteriaBuilder cb = s.getCriteriaBuilder();
            CriteriaQuery<User> cr = cb.createQuery(User.class);
            Root<User> root = cr.from(User.class);
            root.fetch("subscribed_ids", JoinType.LEFT);
            cr.select(root).where(cb.equal(root.get("sessionId"), session));

            return s.createQuery(cr).uniqueResult();
        }
    }

    public static Channel getChannelFromId(SharedSessionContract s, String id) {
        CriteriaBuilder cb = s.getCriteriaBuilder();
        CriteriaQuery<Channel> cr = cb.createQuery(Channel.class);
        Root<Channel> root = cr.from(Channel.class);
        cr.select(root).where(cb.equal(root.get("uploader_id"), id));

        return s.createQuery(cr).uniqueResult();
    }

    public static Channel getChannelFromId(String id) {
        try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
            return getChannelFromId(s, id);
        }
    }

    public static List<Channel> getChannelsFromIds(SharedSessionContract s, Collection<String> id) {
        CriteriaBuilder cb = s.getCriteriaBuilder();
        CriteriaQuery<Channel> cr = cb.createQuery(Channel.class);
        Root<Channel> root = cr.from(Channel.class);
        cr.select(root).where(root.get("uploader_id").in(id));

        return s.createQuery(cr).list();
    }

    public static Video getVideoFromId(SharedSessionContract s, String id) {
        CriteriaBuilder cb = s.getCriteriaBuilder();
        CriteriaQuery<Video> cr = cb.createQuery(Video.class);
        Root<Video> root = cr.from(Video.class);
        cr.select(root).where(cb.equal(root.get("id"), id));

        return s.createQuery(cr).uniqueResult();
    }

    public static List<Video> getVideosFromIds(SharedSessionContract s, Collection<String> ids) {
        CriteriaBuilder cb = s.getCriteriaBuilder();
        CriteriaQuery<Video> cr = cb.createQuery(Video.class);
        Root<Video> root = cr.from(Video.class);
        cr.select(root).where(root.get("id").in(ids));

        return s.createQuery(cr).list();
    }

    public static Video getVideoFromId(String id) {
        try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
            return getVideoFromId(s, id);
        }
    }

    public static boolean doesVideoExist(SharedSessionContract s, String id) {
        return s.createQuery("SELECT 1 FROM Video WHERE id = :id", Integer.class)
                .setParameter("id", id)
                .setMaxResults(1)
                .uniqueResultOptional()
                .isPresent();
    }

    public static PlaylistVideo getPlaylistVideoFromId(SharedSessionContract s, String id) {
        CriteriaBuilder cb = s.getCriteriaBuilder();
        CriteriaQuery<PlaylistVideo> cr = cb.createQuery(PlaylistVideo.class);
        Root<PlaylistVideo> root = cr.from(PlaylistVideo.class);
        cr.select(root).where(cb.equal(root.get("id"), id));

        return s.createQuery(cr).uniqueResult();
    }

    public static Playlist getPlaylistFromId(SharedSessionContract s, String id) {
        CriteriaBuilder cb = s.getCriteriaBuilder();
        CriteriaQuery<Playlist> cr = cb.createQuery(Playlist.class);
        Root<Playlist> root = cr.from(Playlist.class);
        cr.select(root).where(cb.equal(root.get("playlist_id"), UUID.fromString(id)));

        return s.createQuery(cr).uniqueResult();
    }

    public static Playlist getPlaylistFromId(String id) {
        try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
            return getPlaylistFromId(s, id);
        }
    }

    public static List<PlaylistVideo> getPlaylistVideosFromPlaylistId(SharedSessionContract s, String id, boolean fetchChannel) {
        var query = s.createNativeQuery("SELECT {playlist_videos.*}, {channels.*} FROM playlist_videos JOIN channels ON playlist_videos.uploader_id = channels.uploader_id JOIN playlists_videos_ids ON playlist_videos.id = playlists_videos_ids.videos_id JOIN playlists ON playlists.id = playlists_videos_ids.playlist_id WHERE playlists.playlist_id = :id ORDER BY playlists_videos_ids.videos_order ASC")
                .addEntity("playlist_videos", PlaylistVideo.class)
                .addEntity("channels", Channel.class)
                .setParameter("id", UUID.fromString(id));

        return query.getResultList().stream().map(o -> {
            var arr = (Object[]) o;
            var pv = ((PlaylistVideo) arr[0]);
            pv.setChannel((Channel) arr[1]);
            return pv;
        }).toList();
    }

    public static List<PlaylistVideo> getPlaylistVideosFromPlaylistId(String id, boolean fetchChannel) {
        try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
            return getPlaylistVideosFromPlaylistId(s, id, fetchChannel);
        }
    }

    public static List<PlaylistVideo> getPlaylistVideosFromIds(SharedSessionContract s, Collection<String> id) {
        CriteriaBuilder cb = s.getCriteriaBuilder();
        CriteriaQuery<PlaylistVideo> cr = cb.createQuery(PlaylistVideo.class);
        Root<PlaylistVideo> root = cr.from(PlaylistVideo.class);
        cr.select(root).where(root.get("id").in(id));

        return s.createQuery(cr).list();
    }

    public static PubSub getPubSubFromId(SharedSessionContract s, String id) {
        CriteriaBuilder cb = s.getCriteriaBuilder();
        CriteriaQuery<PubSub> cr = cb.createQuery(PubSub.class);
        Root<PubSub> root = cr.from(PubSub.class);
        cr.select(root).where(cb.equal(root.get("id"), id));

        return s.createQuery(cr).uniqueResult();
    }

    public static PubSub getPubSubFromId(String id) {
        try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
            return getPubSubFromId(s, id);
        }
    }

    public static Channel saveChannel(String channelId) {

        if (!ChannelHelpers.isValidId(channelId))
            return null;


        final ChannelInfo info;

        try {
            info = ChannelInfo.getInfo("https://youtube.com/channel/" + channelId);
        } catch (IOException | ExtractionException e) {
            ExceptionUtils.rethrow(e);
            return null;
        }

        var channel = new Channel(channelId, StringUtils.abbreviate(info.getName(), 100),
                info.getAvatars().isEmpty() ? null : info.getAvatars().getLast().getUrl(), info.isVerified());

        try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
            var tr = s.beginTransaction();
            s.insert(channel);
            tr.commit();
        } catch (Exception e) {
            ExceptionHandler.handle(e);
        }

        Multithreading.runAsync(() -> {
            try {
                PubSubHelper.subscribePubSub(channelId);
            } catch (IOException e) {
                ExceptionHandler.handle(e);
            }
        });

        Multithreading.runAsync(() -> {
            CollectionUtils.collectPreloadedTabs(info.getTabs())
                    .stream()
                    .parallel()
                    .mapMulti((tab, consumer) -> {
                        try {
                            ChannelTabInfo.getInfo(YOUTUBE_SERVICE, tab)
                                    .getRelatedItems()
                                    .forEach(consumer);
                        } catch (ExtractionException | IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .filter(StreamInfoItem.class::isInstance)
                    .map(StreamInfoItem.class::cast)
                    .forEach(item -> {
                        long time = item.getUploadDate() != null
                                ? item.getUploadDate().offsetDateTime().toInstant().toEpochMilli()
                                : System.currentTimeMillis();
                        if ((System.currentTimeMillis() - time) < TimeUnit.DAYS.toMillis(Constants.FEED_RETENTION))
                            VideoHelpers.handleNewVideo(item.getUrl(), time, channel);
                    });
        });

        return channel;
    }

    public static void refreshChannelVideos(String channelId) {
        if (!ChannelHelpers.isValidId(channelId)) {
            System.err.println("  [SKIP] Invalid channel ID: " + channelId);
            return;
        }

        // Circuit breaker check - prevent hammering YouTube when it's down
        if (consecutiveRefreshFailures.get() >= MAX_FAILURES_BEFORE_CIRCUIT_BREAK) {
            long now = System.currentTimeMillis();
            // Reset circuit breaker after cooldown period
            if (now > circuitBreakerResetTime) {
                System.out.println("🔄 Circuit breaker RESET - attempting refresh again");
                consecutiveRefreshFailures.set(0);
            } else {
                long remainingSeconds = (circuitBreakerResetTime - now) / 1000;
                System.err.println("⛔ Circuit breaker OPEN - skipping " + channelId + " (resets in " + remainingSeconds + "s)");
                return;
            }
        }

        // Validate channel exists in database before making YouTube API call
        final Channel channel = getChannelFromId(channelId);
        if (channel == null) {
            System.err.println("  [ERROR] Channel not found in database: " + channelId);
            return;
        }

        // Mark channel as "in progress" with -1 to prevent duplicate concurrent refreshes
        updateChannelRefreshTime(channelId, -1);
        System.out.println("  [REFRESH] Starting: " + channelId + " (" + channel.getUploader() + ")");

        try {
            final ChannelInfo info = ChannelInfo.getInfo("https://youtube.com/channel/" + channelId);

            // Update channel info
            try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
                ChannelHelpers.updateChannel(s, channel, StringUtils.abbreviate(info.getName(), 100),
                        info.getAvatars().isEmpty() ? null : info.getAvatars().getLast().getUrl(), info.isVerified());
            }

            // Fetch latest videos (same logic as initial subscription)
            CollectionUtils.collectPreloadedTabs(info.getTabs())
                    .stream()
                    .parallel()
                    .mapMulti((tab, consumer) -> {
                        try {
                            ChannelTabInfo.getInfo(YOUTUBE_SERVICE, tab)
                                    .getRelatedItems()
                                    .forEach(consumer);
                        } catch (ExtractionException | IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .filter(StreamInfoItem.class::isInstance)
                    .map(StreamInfoItem.class::cast)
                    .forEach(item -> {
                        long time = item.getUploadDate() != null
                                ? item.getUploadDate().offsetDateTime().toInstant().toEpochMilli()
                                : System.currentTimeMillis();
                        if ((System.currentTimeMillis() - time) < TimeUnit.DAYS.toMillis(Constants.FEED_RETENTION))
                            VideoHelpers.handleNewVideo(item.getUrl(), time, channel);
                    });

            // Update database with actual refresh timestamp after successful completion
            updateChannelRefreshTime(channelId, System.currentTimeMillis());
            System.out.println("  [SUCCESS] Completed: " + channelId + " (" + channel.getUploader() + ")");

            // Reset failure count on success
            consecutiveRefreshFailures.set(0);

        } catch (IOException | ExtractionException e) {
            // On failure, clear the "in progress" marker so channel can be retried later
            updateChannelRefreshTime(channelId, System.currentTimeMillis() - TimeUnit.HOURS.toMillis(Constants.CHANNEL_REFRESH_WINDOW_HOURS));
            System.err.println("  [FAILED] " + channelId + ": " + e.getMessage());
            
            int failures = consecutiveRefreshFailures.incrementAndGet();
            if (failures >= MAX_FAILURES_BEFORE_CIRCUIT_BREAK) {
                circuitBreakerResetTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(CIRCUIT_BREAKER_RESET_MINUTES);
                System.err.println("⛔ CIRCUIT BREAKER OPENED after " + failures + " consecutive failures!");
                System.err.println("   Will reset at: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(circuitBreakerResetTime)));
                System.err.println("   Reason: Preventing YouTube API abuse/ban");
            } else {
                System.err.println("   Consecutive failures: " + failures + "/" + MAX_FAILURES_BEFORE_CIRCUIT_BREAK);
            }
            ExceptionHandler.handle(e);
        }
    }

    /**
     * Check if a channel should be refreshed based on the last refresh time
     * @param channelId The channel ID to check
     * @param maxAgeMillis Maximum age in milliseconds (e.g., 5 minutes)
     * @return true if the channel should be refreshed, false if it's still fresh
     */
    public static boolean shouldRefreshChannel(String channelId, long maxAgeMillis) {
        try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
            ChannelRefreshTracking tracking = s.get(ChannelRefreshTracking.class, channelId);
            if (tracking == null) {
                return true; // Never refreshed, should refresh
            }
            return (System.currentTimeMillis() - tracking.getLastRefreshed()) > maxAgeMillis;
        } catch (Exception e) {
            ExceptionHandler.handle(e);
            return true; // On error, allow refresh
        }
    }

    /**
     * Batch check which channels need refreshing - efficient for large channel lists
     * @param channelIds Set of channel IDs to check
     * @param maxAgeMillis Maximum age in milliseconds (e.g., 5 minutes)
     * @return Set of channel IDs that need refreshing
     */
    public static Set<String> getChannelsNeedingRefresh(Set<String> channelIds, long maxAgeMillis) {
        if (channelIds.isEmpty()) {
            return Set.of();
        }

        try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
            long cutoffTime = System.currentTimeMillis() - maxAgeMillis;

            // Fetch all refresh timestamps in one query
            List<ChannelRefreshTracking> trackings = s.createQuery(
                    "FROM ChannelRefreshTracking WHERE channelId IN :channelIds", 
                    ChannelRefreshTracking.class)
                    .setParameter("channelIds", channelIds)
                    .getResultList();

            // Build set of channels that are fresh or currently being refreshed
            // Fresh = lastRefreshed > cutoffTime
            // In progress = lastRefreshed == -1
            Set<String> skipChannels = trackings.stream()
                    .filter(t -> t.getLastRefreshed() > cutoffTime || t.getLastRefreshed() == -1)
                    .map(ChannelRefreshTracking::getChannelId)
                    .collect(Collectors.toSet());

            // Return channels that need refresh (not in skip set)
            return channelIds.stream()
                    .filter(id -> !skipChannels.contains(id))
                    .collect(Collectors.toSet());

        } catch (Exception e) {
            ExceptionHandler.handle(e);
            // On error, refresh all channels to be safe
            return new HashSet<>(channelIds);
        }
    }

    /**
     * Update the last refresh time for a channel
     * @param channelId The channel ID
     * @param timestamp The timestamp when the channel was refreshed
     */
    public static void updateChannelRefreshTime(String channelId, long timestamp) {
        try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
            var tr = s.beginTransaction();

            try {
                // Use UPSERT to handle concurrent access safely
                // PostgreSQL, YugabyteDB, CockroachDB syntax
                s.createNativeMutationQuery(
                        "INSERT INTO channel_refresh_tracking (channel_id, last_refreshed) " +
                                "VALUES (?, ?) " +
                                "ON CONFLICT (channel_id) DO UPDATE SET last_refreshed = EXCLUDED.last_refreshed")
                        .setParameter(1, channelId)
                        .setParameter(2, timestamp)
                        .executeUpdate();

                tr.commit();
            } catch (Exception e) {
                if (tr != null && tr.isActive()) {
                    tr.rollback();
                }
                throw e;
            }
        } catch (Exception e) {
            ExceptionHandler.handle(e);
        }
    }
}
