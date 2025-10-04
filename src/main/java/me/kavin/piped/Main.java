package me.kavin.piped;

import io.activej.inject.Injector;
import io.sentry.Sentry;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import jakarta.persistence.criteria.CriteriaBuilder;
import me.kavin.piped.consts.Constants;
import me.kavin.piped.server.ServerLauncher;
import me.kavin.piped.utils.*;
import me.kavin.piped.utils.matrix.SyncRunner;
import me.kavin.piped.utils.obj.MatrixHelper;
import me.kavin.piped.utils.obj.db.PlaylistVideo;
import me.kavin.piped.utils.obj.db.PubSub;
import me.kavin.piped.utils.obj.db.Video;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hibernate.Session;
import org.hibernate.StatelessSession;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import rocks.kavin.reqwest4j.ReqwestUtils;

import java.security.Security;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Set;

import static me.kavin.piped.consts.Constants.*;

public class Main {

    public static void main(String[] args) throws Exception {

        Security.setProperty("crypto.policy", "unlimited");
        Security.addProvider(new BouncyCastleProvider());

        ReqwestUtils.init(REQWEST_PROXY, REQWEST_PROXY_USER, REQWEST_PROXY_PASS);

        NewPipe.init(new DownloaderImpl(), new Localization("en", "US"), ContentCountry.DEFAULT);
        if (!StringUtils.isEmpty(Constants.BG_HELPER_URL))
            YoutubeStreamExtractor.setPoTokenProvider(new BgPoTokenProvider(Constants.BG_HELPER_URL));
        YoutubeParsingHelper.setConsentAccepted(CONSENT_COOKIE);

        // Warm up the extractor
        try {
            StreamInfo.getInfo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        } catch (Exception ignored) {
        }

        // Find country code, used for georestricted videos
        Thread.ofVirtual().start(() -> {
            try {
                var html = RequestUtils.sendGet("https://www.youtube.com/").get();
                var regex = Pattern.compile("GL\":\"([A-Z]{2})\"", Pattern.MULTILINE);
                var matcher = regex.matcher(html);
                if (matcher.find()) {
                    YOUTUBE_COUNTRY = matcher.group(1);
                }
            } catch (Exception ignored) {
                System.err.println("Failed to get country from YouTube!");
            }
        });

        Sentry.init(options -> {
            options.setDsn(Constants.SENTRY_DSN);
            options.setRelease(Constants.VERSION);
            options.addIgnoredExceptionForType(ErrorResponse.class);
            options.setTracesSampleRate(0.1);
        });

        Injector.useSpecializer();

        try {
            LiquibaseHelper.init();
        } catch (Exception e) {
            ExceptionHandler.handle(e);
            System.exit(1);
        }

        Multithreading.runAsync(() -> Thread.ofVirtual().start(new SyncRunner(
                new OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build(),
                MATRIX_SERVER,
                MatrixHelper.MATRIX_TOKEN)
        ));

        new Timer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                System.out.printf("ThrottlingCache: %o entries%n", YoutubeJavaScriptPlayerManager.getThrottlingParametersCacheSize());
                YoutubeJavaScriptPlayerManager.clearThrottlingParametersCache();
            }
        }, 0, TimeUnit.MINUTES.toMillis(60));

        if (!Constants.DISABLE_SERVER)
            new Thread(() -> {
                try {
                    new ServerLauncher().launch(args);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).start();

        try (Session ignored = DatabaseSessionFactory.createSession()) {
            System.out.println("Database connection is ready!");
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }

        // Close the HikariCP connection pool
        Runtime.getRuntime().addShutdownHook(new Thread(DatabaseSessionFactory::close));

        if (Constants.DISABLE_TIMERS)
            return;

        new Timer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {

                    List<String> channelIds = s.createNativeQuery("SELECT id FROM pubsub WHERE subbed_at < :subbedTime AND id IN (" +
                                    "SELECT DISTINCT channel FROM users_subscribed" +
                                    " UNION " +
                                    "SELECT id FROM unauthenticated_subscriptions WHERE subscribed_at > :unauthSubbed" +
                                    ")", String.class)
                            .setParameter("subbedTime", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(4))
                            .setParameter("unauthSubbed", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(Constants.SUBSCRIPTIONS_EXPIRY))
                            .stream()
                            .filter(Objects::nonNull)
                            .distinct()
                            .collect(Collectors.toCollection(ObjectArrayList::new));

                    Collections.shuffle(channelIds);

                    var queue = new ConcurrentLinkedQueue<>(channelIds);

                    System.out.println("PubSub: queue size - " + queue.size() + " channels");

                    for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
                        new Thread(() -> {

                            Object o = new Object();

                            String channelId;
                            while ((channelId = queue.poll()) != null) {
                                try {
                                    CompletableFuture<?> future = PubSubHelper.subscribePubSub(channelId);

                                    if (future == null)
                                        continue;

                                    future.whenComplete((resp, throwable) -> {
                                        synchronized (o) {
                                            o.notify();
                                        }
                                    });

                                    synchronized (o) {
                                        o.wait();
                                    }

                                } catch (Exception e) {
                                    ExceptionHandler.handle(e);
                                }
                            }
                        }, "PubSub-" + i).start();
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 0, TimeUnit.MINUTES.toMillis(90));

        new Timer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {

                    s.createNativeQuery("SELECT channel_id.channel FROM " +
                                    "(SELECT DISTINCT channel FROM users_subscribed UNION SELECT id FROM unauthenticated_subscriptions WHERE subscribed_at > :unauthSubbed) " +
                                    "channel_id LEFT JOIN pubsub on pubsub.id = channel_id.channel " +
                                    "WHERE pubsub.id IS NULL", String.class)
                            .setParameter("unauthSubbed", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(Constants.SUBSCRIPTIONS_EXPIRY))
                            .getResultStream()
                            .parallel()
                            .filter(ChannelHelpers::isValidId)
                            .forEach(id -> Multithreading.runAsyncLimitedPubSub(() -> {
                                try (StatelessSession sess = DatabaseSessionFactory.createStatelessSession()) {
                                    var pubsub = new PubSub(id, -1);
                                    var tr = sess.beginTransaction();
                                    sess.insert(pubsub);
                                    tr.commit();
                                }
                            }));

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 0, TimeUnit.DAYS.toMillis(1));

        new Timer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {

                    var cb = s.getCriteriaBuilder();
                    var cd = cb.createCriteriaDelete(Video.class);
                    var root = cd.from(Video.class);
                    cd.where(cb.lessThan(root.get("uploaded"), System.currentTimeMillis() - TimeUnit.DAYS.toMillis(Constants.FEED_RETENTION)));

                    var tr = s.beginTransaction();

                    var query = s.createMutationQuery(cd);

                    System.out.printf("Cleanup: Removed %o old videos%n", query.executeUpdate());

                    tr.commit();

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 0, TimeUnit.MINUTES.toMillis(60));

        new Timer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {

                    CriteriaBuilder cb = s.getCriteriaBuilder();

                    var pvQuery = cb.createCriteriaDelete(PlaylistVideo.class);
                    var pvRoot = pvQuery.from(PlaylistVideo.class);

                    var subQuery = pvQuery.subquery(String.class);
                    var subRoot = subQuery.from(me.kavin.piped.utils.obj.db.Playlist.class);

                    subQuery.select(subRoot.join("videos").get("id")).distinct(true);

                    pvQuery.where(cb.not(pvRoot.get("id").in(subQuery)));

                    var tr = s.beginTransaction();
                    s.createMutationQuery(pvQuery).executeUpdate();
                    tr.commit();
                }
            }
        }, 0, TimeUnit.MINUTES.toMillis(60));

        // Periodic channel refresh for feed updates (since PubSub only works for public instances)
        // Only enabled for private self-hosted instances via ENABLE_PERIODIC_CHANNEL_REFRESH env variable
        if (Constants.ENABLE_PERIODIC_CHANNEL_REFRESH) {
            System.out.println("=== Channel Refresh Feature ENABLED ===");
            System.out.printf("  Initial delay: %d minutes%n", Constants.CHANNEL_REFRESH_INITIAL_DELAY_MINUTES);
            System.out.printf("  Refresh interval: %d hours%n", Constants.CHANNEL_REFRESH_WINDOW_HOURS);
            System.out.printf("  Next refresh at: %s%n", 
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(
                    new java.util.Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(Constants.CHANNEL_REFRESH_INITIAL_DELAY_MINUTES))));
            
            new Timer().scheduleAtFixedRate(new TimerTask() {
                private volatile boolean isRunning = false;
                
                @Override
                public void run() {
                    long startTime = System.currentTimeMillis();
                    System.out.println("\n=== Channel Refresh Task Started ===");
                    System.out.printf("Time: %s%n", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
                    
                    // Prevent overlapping executions
                    if (isRunning) {
                        System.out.println("⚠️  Channel refresh already running, skipping this cycle");
                        return;
                    }
                    
                    isRunning = true;
                    int successCount = 0;
                    int failureCount = 0;
                    
                    try (StatelessSession s = DatabaseSessionFactory.createStatelessSession()) {
                        System.out.println("Step 1: Querying database for subscribed channels...");

                        // Get all subscribed channels from authenticated users and unauthenticated subscriptions
                        Set<String> allChannelIds = s.createNativeQuery(
                                "SELECT DISTINCT channel FROM users_subscribed UNION " +
                                "SELECT id FROM unauthenticated_subscriptions WHERE subscribed_at > :unauthSubbed", 
                                String.class)
                                .setParameter("unauthSubbed", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(Constants.SUBSCRIPTIONS_EXPIRY))
                                .stream()
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet());

                        System.out.printf("  Found %d total subscribed channels%n", allChannelIds.size());

                        if (allChannelIds.isEmpty()) {
                            System.out.println("⚠️  No subscribed channels found, nothing to refresh");
                            return;
                        }

                        System.out.println("Step 2: Checking which channels need refresh...");
                        // Smart filtering: only refresh channels that haven't been refreshed recently
                        Set<String> channelsNeedingRefresh = DatabaseHelper.getChannelsNeedingRefresh(
                                allChannelIds, TimeUnit.HOURS.toMillis(Constants.CHANNEL_REFRESH_WINDOW_HOURS));

                        System.out.printf("  %d channels need refresh (stale or never refreshed)%n", channelsNeedingRefresh.size());
                        System.out.printf("  %d channels are fresh (skip)%n", allChannelIds.size() - channelsNeedingRefresh.size());

                        if (channelsNeedingRefresh.isEmpty()) {
                            System.out.printf("✅ All %d channels are fresh, nothing to do%n", allChannelIds.size());
                            return;
                        }

                        System.out.printf("Step 3: Refreshing %d of %d channels...%n", 
                                channelsNeedingRefresh.size(), allChannelIds.size());

                        List<String> channelList = new ArrayList<>(channelsNeedingRefresh);
                        Collections.shuffle(channelList);

                        var queue = new ConcurrentLinkedQueue<>(channelList);
                        
                        int threadCount = Math.min(Runtime.getRuntime().availableProcessors(), channelList.size());
                        System.out.printf("  Using %d worker threads%n", threadCount);
                        
                        final java.util.concurrent.atomic.AtomicInteger processedCount = new java.util.concurrent.atomic.AtomicInteger(0);
                        final java.util.concurrent.atomic.AtomicInteger localSuccessCount = new java.util.concurrent.atomic.AtomicInteger(0);
                        final java.util.concurrent.atomic.AtomicInteger localFailureCount = new java.util.concurrent.atomic.AtomicInteger(0);
                        
                        for (int i = 0; i < threadCount; i++) {
                            new Thread(() -> {
                                String channelId;
                                while ((channelId = queue.poll()) != null) {
                                    try {
                                        int current = processedCount.incrementAndGet();
                                        if (current % 10 == 0 || current == channelsNeedingRefresh.size()) {
                                            System.out.printf("  Progress: %d/%d channels processed%n", current, channelsNeedingRefresh.size());
                                        }
                                        DatabaseHelper.refreshChannelVideos(channelId);
                                        localSuccessCount.incrementAndGet();
                                        // Small delay to avoid overwhelming YouTube
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        break; // Exit gracefully on interrupt
                                    } catch (Exception e) {
                                        localFailureCount.incrementAndGet();
                                        ExceptionHandler.handle(e);
                                    }
                                }
                            }, "Channel-Refresh-" + i).start();
                        }
                        
                        // Wait for all threads to complete (with timeout)
                        int waitCount = 0;
                        while (processedCount.get() < channelsNeedingRefresh.size() && waitCount < 600) { // Max 60 seconds wait
                            Thread.sleep(100);
                            waitCount++;
                        }
                        
                        successCount = localSuccessCount.get();
                        failureCount = localFailureCount.get();

                    } catch (Exception e) {
                        System.err.println("❌ Error in channel refresh task:");
                        e.printStackTrace();
                    } finally {
                        isRunning = false;
                        long duration = System.currentTimeMillis() - startTime;
                        System.out.println("\n=== Channel Refresh Task Completed ===");
                        System.out.printf("  Duration: %.2f seconds%n", duration / 1000.0);
                        System.out.printf("  Success: %d channels%n", successCount);
                        System.out.printf("  Failures: %d channels%n", failureCount);
                        System.out.printf("  Next refresh at: %s%n", 
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(
                                new java.util.Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(Constants.CHANNEL_REFRESH_WINDOW_HOURS))));
                        System.out.println("========================================\n");
                    }
                }
            }, TimeUnit.MINUTES.toMillis(Constants.CHANNEL_REFRESH_INITIAL_DELAY_MINUTES), 
               TimeUnit.HOURS.toMillis(Constants.CHANNEL_REFRESH_WINDOW_HOURS)); // Configurable initial delay and interval
        } else {
            System.out.println("=== Channel Refresh Feature DISABLED ===");
            System.out.println("  To enable: Set ENABLE_PERIODIC_CHANNEL_REFRESH=true in config.properties");
        }

    }
}
