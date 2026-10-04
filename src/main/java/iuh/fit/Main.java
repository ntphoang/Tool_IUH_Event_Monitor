package iuh.fit;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    // =========================================================
    // CONFIG
    // =========================================================

    private static final String IUH_URL =
            "https://doantn.iuh.edu.vn/doanvien.html@211@Tra-cuu";

    private static final Path COOKIE_FILE =
            Paths.get("iuh_cookie.txt");

    private static final Path ACTIVITIES_FILE =
            Paths.get("activities.txt");

    private static final Path TELEGRAM_OFFSET_FILE =
            Paths.get("telegram_offset.txt");

    private static final String ENV_IUH_COOKIE =
            "IUH_COOKIE";

    private static final String ENV_BOT_TOKEN =
            "TELEGRAM_BOT_TOKEN";

    private static final String ENV_CHAT_ID =
            "TELEGRAM_CHAT_ID";

    // ID Telegram cá nhân của người được phép /cookie
    private static final String ENV_ADMIN_ID =
            "TELEGRAM_ADMIN_ID";

    // Check IUH mỗi 30 giây
    private static final int ACTIVITY_CHECK_SECONDS = 30;

    // Telegram polling mỗi 5 giây
    private static final int TELEGRAM_CHECK_SECONDS = 5;

    // Heartbeat mỗi 30 phút
    private static final int HEARTBEAT_MINUTES = 30;


    // =========================================================
    // HTTP
    // =========================================================

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();


    // =========================================================
    // RUNTIME STATE
    // =========================================================

    private static volatile String currentCookie;

    private static volatile boolean cookieExpiredNotified = false;

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");


    // =========================================================
    // MAIN
    // =========================================================

    public static void main(String[] args) {

        printBanner();

        initializeCookie();

        ScheduledExecutorService scheduler =
                Executors.newScheduledThreadPool(3);

        // -----------------------------------------------------
        // 1. Check IUH mỗi 30 giây
        // -----------------------------------------------------

        scheduler.scheduleAtFixedRate(
                Main::checkActivities,
                0,
                ACTIVITY_CHECK_SECONDS,
                TimeUnit.SECONDS
        );

        // -----------------------------------------------------
        // 2. Check Telegram mỗi 5 giây
        // -----------------------------------------------------

        scheduler.scheduleAtFixedRate(
                Main::checkTelegramMessages,
                0,
                TELEGRAM_CHECK_SECONDS,
                TimeUnit.SECONDS
        );

        // -----------------------------------------------------
        // 3. Heartbeat mỗi 30 phút
        // -----------------------------------------------------

        scheduler.scheduleAtFixedRate(
                Main::sendHeartbeat,
                HEARTBEAT_MINUTES,
                HEARTBEAT_MINUTES,
                TimeUnit.MINUTES
        );

        System.out.println("🚀 Tool đang chạy...\n");

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    System.out.println("\n🛑 Đang dừng tool...");
                    scheduler.shutdown();
                })
        );
    }


    // =========================================================
    // BANNER
    // =========================================================

    private static void printBanner() {

        System.out.println("========================================");
        System.out.println("       IUH EVENT MONITOR");
        System.out.println("========================================");

        System.out.println(
                "IUH      : mỗi " +
                        ACTIVITY_CHECK_SECONDS +
                        " giây"
        );

        System.out.println(
                "Telegram : mỗi " +
                        TELEGRAM_CHECK_SECONDS +
                        " giây"
        );

        System.out.println(
                "Heartbeat: mỗi " +
                        HEARTBEAT_MINUTES +
                        " phút"
        );

        System.out.println("========================================");
    }


    // =========================================================
    // COOKIE
    // =========================================================

    private static void initializeCookie() {

        try {

            // -------------------------------------------------
            // Ưu tiên cookie đã lưu trong file
            // -------------------------------------------------

            if (Files.exists(COOKIE_FILE)) {

                String cookie =
                        Files.readString(COOKIE_FILE).trim();

                if (!cookie.isBlank()) {

                    currentCookie = cookie;

                    System.out.println(
                            "🍪 Đã load cookie IUH."
                    );

                    return;
                }
            }

            // -------------------------------------------------
            // Nếu chưa có file thì thử ENV
            // -------------------------------------------------

            String envCookie =
                    System.getenv(ENV_IUH_COOKIE);

            if (envCookie != null &&
                    !envCookie.isBlank()) {

                currentCookie = envCookie.trim();

                saveCookie(currentCookie);

                System.out.println(
                        "🍪 Đã load cookie IUH từ ENV."
                );

                return;
            }

            System.out.println(
                    "⚠️ Chưa có cookie IUH."
            );

            System.out.println(
                    "📱 Hãy gửi Telegram:"
            );

            System.out.println(
                    "/cookie YOUR_COOKIE"
            );

        } catch (Exception e) {

            System.err.println(
                    "❌ Không thể load cookie: " +
                            e.getMessage()
            );
        }
    }


    private static synchronized void saveCookie(
            String cookie
    ) {

        try {

            Files.writeString(
                    COOKIE_FILE,
                    cookie,
                    StandardCharsets.UTF_8
            );

            currentCookie = cookie;

            System.out.println(
                    "💾 Đã lưu cookie IUH."
            );

        } catch (IOException e) {

            System.err.println(
                    "❌ Không thể lưu cookie: " +
                            e.getMessage()
            );
        }
    }


    // =========================================================
    // CHECK IUH
    // =========================================================

    private static void checkActivities() {

        System.out.println(
                "\n[" +
                        LocalDateTime.now()
                                .format(TIME_FORMATTER) +
                        "] Checking IUH..."
        );

        if (currentCookie == null ||
                currentCookie.isBlank()) {

            System.out.println(
                    "⚠️ Chưa có cookie IUH."
            );

            return;
        }

        try {

            Document document =
                    fetchIUHPage();

            // -------------------------------------------------
            // Kiểm tra cookie hết hạn
            // -------------------------------------------------

            if (isLoginPage(document)) {

                handleCookieExpired();

                return;
            }

            // Cookie hợp lệ
            cookieExpiredNotified = false;

            // -------------------------------------------------
            // Parse activities
            // -------------------------------------------------

            List<Activity> currentActivities =
                    parseActivities(document);

            System.out.println(
                    "📋 Tìm thấy " +
                            currentActivities.size() +
                            " hoạt động."
            );

            // -------------------------------------------------
            // Load state cũ
            // -------------------------------------------------

            Map<String, Activity> oldActivities =
                    loadActivities();

            // -------------------------------------------------
            // First run
            // -------------------------------------------------

            if (oldActivities.isEmpty() &&
                    !Files.exists(ACTIVITIES_FILE)) {

                saveActivities(currentActivities);

                System.out.println(
                        "📝 Lần chạy đầu tiên → " +
                                "lưu dữ liệu hiện tại, " +
                                "không gửi thông báo."
                );

                return;
            }

            // -------------------------------------------------
            // Find new activities
            // -------------------------------------------------

            List<Activity> newActivities =
                    new ArrayList<>();

            for (Activity current :
                    currentActivities) {

                if (!oldActivities.containsKey(
                        current.id
                )) {

                    newActivities.add(current);
                }
            }

            // -------------------------------------------------
            // Send notification
            // -------------------------------------------------

            if (!newActivities.isEmpty()) {

                System.out.println(
                        "🆕 Phát hiện " +
                                newActivities.size() +
                                " hoạt động mới."
                );

                sendNewActivityNotification(
                        newActivities
                );

            } else {

                System.out.println(
                        "✓ Không có hoạt động mới."
                );
            }

            // -------------------------------------------------
            // Save latest state
            // -------------------------------------------------

            saveActivities(currentActivities);

        } catch (Exception e) {

            System.err.println(
                    "❌ Lỗi khi check IUH: " +
                            e.getMessage()
            );
        }
    }


    // =========================================================
    // FETCH IUH
    // =========================================================

    private static Document fetchIUHPage()
            throws IOException {

        Connection connection =
                Jsoup.connect(IUH_URL)
                        .method(Connection.Method.GET)
                        .timeout(20_000)
                        .followRedirects(true)
                        .userAgent(
                                "Mozilla/5.0 " +
                                        "(Windows NT 10.0; Win64; x64) " +
                                        "AppleWebKit/537.36 " +
                                        "(KHTML, like Gecko) " +
                                        "Chrome/154.0.0.0 Safari/537.36"
                        );

        connection.header(
                "Cookie",
                currentCookie
        );

        connection.header(
                "Accept",
                "text/html,application/xhtml+xml"
        );

        return connection.get();
    }


    // =========================================================
    // LOGIN PAGE DETECTION
    // =========================================================

    private static boolean isLoginPage(
            Document document
    ) {

        // Meta refresh
        if (!document.select(
                "meta[http-equiv=refresh]"
        ).isEmpty()) {

            return true;
        }

        // Password input
        if (!document.select(
                "input[type=password]"
        ).isEmpty()) {

            return true;
        }

        // Title
        String title =
                document.title()
                        .toLowerCase();

        if (title.contains("đăng nhập") ||
                title.contains("login")) {

            return true;
        }

        // Body text
        String body =
                document.body()
                        .text()
                        .toLowerCase();

        return body.contains("đăng nhập") &&
                (
                        body.contains("mật khẩu") ||
                                body.contains("password")
                );
    }


    // =========================================================
    // COOKIE EXPIRED
    // =========================================================

    private static void handleCookieExpired() {

        System.out.println(
                "❌ Cookie IUH đã hết hạn."
        );

        if (cookieExpiredNotified) {

            return;
        }

        String message =
                """
                ⚠️ IUH EVENT MONITOR

                🍪 Cookie IUH đã hết hạn.

                Vui lòng gửi cookie mới:
                /cookie YOUR_COOKIE
                """;

        try {

            sendTelegramMessage(message);

            // Chỉ đánh dấu đã gửi sau khi gửi thành công
            cookieExpiredNotified = true;

        } catch (Exception e) {

            System.err.println(
                    "❌ Không gửi được cảnh báo cookie: " +
                            e.getMessage()
            );
        }
    }


    // =========================================================
    // PARSE ACTIVITIES
    // =========================================================

    private static List<Activity> parseActivities(
            Document document
    ) {

        List<Activity> activities =
                new ArrayList<>();

        Elements tables =
                document.select("table");

        for (Element table : tables) {

            Elements headers =
                    table.select("thead th");

            if (headers.isEmpty()) {
                continue;
            }

            String headerText =
                    table.text();

            if (!headerText.contains(
                    "Hoạt động(T/gia)"
            )) {
                continue;
            }

            if (!headerText.contains(
                    "Kết thúc đăng ký"
            )) {
                continue;
            }

            Elements rows =
                    table.select("tbody tr");

            for (Element row : rows) {

                try {

                    Activity activity =
                            parseActivity(
                                    row,
                                    document
                            );

                    if (activity != null) {

                        activities.add(activity);
                    }

                } catch (Exception e) {

                    System.err.println(
                            "⚠️ Không thể parse activity: " +
                                    e.getMessage()
                    );
                }
            }
        }

        return activities;
    }


    // =========================================================
    // PARSE ONE ACTIVITY
    // =========================================================

    private static Activity parseActivity(
            Element row,
            Document document
    ) {

        Elements cells =
                row.select("> td");

        if (cells.size() < 6) {
            return null;
        }

        // -----------------------------------------------------
        // Activity link
        // -----------------------------------------------------

        Element link =
                cells.get(1).selectFirst("a");

        if (link == null) {
            return null;
        }

        String rawName =
                link.text().trim();

        // -----------------------------------------------------
        // ID
        // -----------------------------------------------------

        String target =
                link.attr("data-target");

        String id =
                extractActivityId(target);

        if (id.isBlank()) {

            id = rawName;
        }

        // -----------------------------------------------------
        // Slot
        // -----------------------------------------------------

        SlotInfo slotInfo =
                parseSlot(rawName);

        String cleanName =
                cleanActivityName(rawName);

        // -----------------------------------------------------
        // Semester
        // -----------------------------------------------------

        String semester =
                cells.get(2).text().trim();

        // -----------------------------------------------------
        // Points
        // -----------------------------------------------------

        String points =
                cells.get(3).text().trim();

        // -----------------------------------------------------
        // End date
        // -----------------------------------------------------

        String endDate =
                cells.get(4).text().trim();

        // -----------------------------------------------------
        // Register status
        // -----------------------------------------------------

        String registerStatus =
                cells.get(5).text().trim();

        boolean canRegister =
                slotInfo.remainingSlots > 0;

        // -----------------------------------------------------
        // Detail
        // -----------------------------------------------------

        String detail =
                parseDetail(
                        document,
                        target
                );

        return new Activity(
                id,
                cleanName,
                semester,
                points,
                endDate,
                slotInfo.registeredSlots,
                slotInfo.totalSlots,
                slotInfo.remainingSlots,
                registerStatus,
                canRegister,
                detail
        );
    }


    // =========================================================
    // EXTRACT ID
    // =========================================================

    private static String extractActivityId(
            String target
    ) {

        if (target == null ||
                target.isBlank()) {

            return "";
        }

        Pattern pattern =
                Pattern.compile(
                        "#myModal_(\\d+)"
                );

        Matcher matcher =
                pattern.matcher(target);

        if (matcher.find()) {

            return matcher.group(1);
        }

        return "";
    }


    // =========================================================
    // PARSE SLOT
    // =========================================================

    private static SlotInfo parseSlot(
            String text
    ) {

        Pattern pattern =
                Pattern.compile(
                        "\\((\\d+)\\s*/\\s*(\\d+)\\)"
                );

        Matcher matcher =
                pattern.matcher(text);

        if (!matcher.find()) {

            return new SlotInfo(
                    0,
                    0,
                    0
            );
        }

        int registered =
                Integer.parseInt(
                        matcher.group(1)
                );

        int total =
                Integer.parseInt(
                        matcher.group(2)
                );

        int remaining =
                Math.max(
                        total - registered,
                        0
                );

        return new SlotInfo(
                registered,
                total,
                remaining
        );
    }


    // =========================================================
    // CLEAN ACTIVITY NAME
    // =========================================================

    private static String cleanActivityName(
            String rawName
    ) {

        return rawName
                .replaceAll(
                        "\\s*\\(\\d+\\s*/\\s*\\d+\\)\\s*$",
                        ""
                )
                .trim();
    }


    // =========================================================
    // PARSE DETAIL
    // =========================================================

    private static String parseDetail(
            Document document,
            String target
    ) {

        if (target == null ||
                target.isBlank()) {

            return "";
        }

        Element modal =
                document.selectFirst(target);

        if (modal == null) {

            return "";
        }

        Element body =
                modal.selectFirst(
                        ".modal-body"
                );

        if (body == null) {

            return "";
        }

        // Convert <br> thành newline
        for (Element br :
                body.select("br")) {

            br.replaceWith(
                    new Element("span")
                            .text("\n")
            );
        }

        String text =
                body.text();

        return text
                .replace("\\n", "\n")
                .replaceAll(
                        "\\n\\s*\\n+",
                        "\n"
                )
                .trim();
    }


    // =========================================================
    // EXTRACT TIME
    // =========================================================

    private static String extractTime(
            String detail
    ) {

        if (detail == null ||
                detail.isBlank()) {

            return "";
        }

        // Trường hợp detail bị flatten thành một dòng
        Pattern inlinePattern =
                Pattern.compile(
                        "(?:-\\s*)?Thời gian:\\s*(.*?)(?=\\s*-\\s*Địa điểm:|$)",
                        Pattern.CASE_INSENSITIVE
                );

        Matcher inlineMatcher =
                inlinePattern.matcher(detail);

        if (inlineMatcher.find()) {

            return inlineMatcher
                    .group(1)
                    .trim();
        }

        // Trường hợp detail vẫn giữ newline
        for (String line :
                detail.split("\\R")) {

            String cleaned =
                    line.trim();

            if (cleaned.startsWith(
                    "- Thời gian:"
            )) {

                return cleaned
                        .substring(
                                "- Thời gian:"
                                        .length()
                        )
                        .trim();
            }

            if (cleaned.startsWith(
                    "Thời gian:"
            )) {

                return cleaned
                        .substring(
                                "Thời gian:"
                                        .length()
                        )
                        .trim();
            }
        }

        return "";
    }


    // =========================================================
    // LOAD ACTIVITIES
    // =========================================================

    private static Map<String, Activity>
    loadActivities() {

        Map<String, Activity> activities =
                new HashMap<>();

        if (!Files.exists(ACTIVITIES_FILE)) {

            return activities;
        }

        try {

            List<String> lines =
                    Files.readAllLines(
                            ACTIVITIES_FILE,
                            StandardCharsets.UTF_8
                    );

            for (String line : lines) {

                if (line.isBlank()) {
                    continue;
                }

                Activity activity =
                        Activity.fromLine(line);

                if (activity != null) {

                    activities.put(
                            activity.id,
                            activity
                    );
                }
            }

        } catch (Exception e) {

            System.err.println(
                    "❌ Không thể load activities.txt: " +
                            e.getMessage()
            );
        }

        return activities;
    }


    // =========================================================
    // SAVE ACTIVITIES
    // =========================================================

    private static synchronized void saveActivities(
            List<Activity> activities
    ) {

        try {

            List<String> lines =
                    new ArrayList<>();

            for (Activity activity :
                    activities) {

                lines.add(
                        activity.toLine()
                );
            }

            Files.write(
                    ACTIVITIES_FILE,
                    lines,
                    StandardCharsets.UTF_8
            );

        } catch (Exception e) {

            System.err.println(
                    "❌ Không thể lưu activities.txt: " +
                            e.getMessage()
            );
        }
    }


    // =========================================================
    // TELEGRAM - NEW ACTIVITY
    // =========================================================

    private static void sendNewActivityNotification(
            List<Activity> activities
    ) throws Exception {

        StringBuilder message =
                new StringBuilder();

        message.append(
                "🆕 IUH CÓ HOẠT ĐỘNG MỚI!\n"
        );

        for (Activity activity :
                activities) {

            message.append("\n");

            // -------------------------------------------------
            // Tên hoạt động
            // -------------------------------------------------

            message.append("📌 ")
                    .append(activity.name)
                    .append("\n");

            // -------------------------------------------------
            // Slot status
            // -------------------------------------------------

            if (activity.remainingSlots > 0) {

                message.append(
                        "🟢 CÒN SLOT\n"
                );

            } else {

                message.append(
                        "🔴 HẾT SLOT\n"
                );
            }

            // -------------------------------------------------
            // Điểm
            // -------------------------------------------------

            message.append("⭐️ Điểm: ")
                    .append(activity.points)
                    .append("\n");

            // -------------------------------------------------
            // Slot registered / total
            // -------------------------------------------------

            message.append("👥 Slot: ")
                    .append(activity.registeredSlots)
                    .append("/")
                    .append(activity.totalSlots)
                    .append("\n");

            // -------------------------------------------------
            // Trạng thái
            // -------------------------------------------------

            message.append(
                            "📌 Trạng thái: "
                    )
                    .append(activity.registerStatus)
                    .append("\n");

            // -------------------------------------------------
            // Deadline
            // -------------------------------------------------

            message.append(
                            "⏰ Kết thúc đăng ký: "
                    )
                    .append(activity.endDate)
                    .append("\n");

            // -------------------------------------------------
            // Thời gian
            // -------------------------------------------------

            String time =
                    extractTime(
                            activity.detail
                    );

            if (!time.isBlank()) {

                message.append(
                                "📋 Thời gian: "
                        )
                        .append(time)
                        .append("\n");
            }
        }

        sendTelegramMessage(
                message.toString()
        );
    }


    // =========================================================
    // TELEGRAM - HEARTBEAT
    // =========================================================

    private static void sendHeartbeat() {

        try {

            String currentTime =
                    LocalDateTime.now()
                            .format(TIME_FORMATTER);

            String message =
                    """
                    💚 IUH EVENT MONITOR ĐANG HOẠT ĐỘNG

                    ⏱ Thời gian: %s
                    📋 Kiểm tra IUH: mỗi 30 giây
                    """.formatted(currentTime);

            sendTelegramMessage(message);

            System.out.println(
                    "💚 Heartbeat đã gửi Telegram."
            );

        } catch (Exception e) {

            System.err.println(
                    "❌ Không gửi được heartbeat: " +
                            e.getMessage()
            );
        }
    }


    // =========================================================
    // TELEGRAM - SEND MESSAGE
    // =========================================================

    private static void sendTelegramMessage(
            String message
    ) throws Exception {

        String botToken =
                System.getenv(
                        ENV_BOT_TOKEN
                );

        String chatId =
                System.getenv(
                        ENV_CHAT_ID
                );

        if (botToken == null ||
                botToken.isBlank()) {

            throw new IllegalStateException(
                    "Chưa cấu hình " +
                            ENV_BOT_TOKEN
            );
        }

        if (chatId == null ||
                chatId.isBlank()) {

            throw new IllegalStateException(
                    "Chưa cấu hình " +
                            ENV_CHAT_ID
            );
        }

        String url =
                "https://api.telegram.org/bot" +
                        botToken +
                        "/sendMessage" +
                        "?chat_id=" +
                        URLEncoder.encode(
                                chatId,
                                StandardCharsets.UTF_8
                        ) +
                        "&text=" +
                        URLEncoder.encode(
                                message,
                                StandardCharsets.UTF_8
                        );

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();

        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (response.statusCode() != 200) {

            throw new IOException(
                    "Telegram HTTP " +
                            response.statusCode() +
                            ": " +
                            response.body()
            );
        }
    }


    // =========================================================
    // TELEGRAM - POLLING
    // =========================================================

    private static void checkTelegramMessages() {

        String botToken =
                System.getenv(
                        ENV_BOT_TOKEN
                );

        if (botToken == null ||
                botToken.isBlank()) {

            return;
        }

        try {

            long offset =
                    loadTelegramOffset();

            String url =
                    "https://api.telegram.org/bot" +
                            botToken +
                            "/getUpdates" +
                            "?timeout=0" +
                            "&offset=" +
                            (offset + 1);

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .GET()
                            .build();

            HttpResponse<String> response =
                    HTTP_CLIENT.send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    );

            if (response.statusCode() != 200) {

                System.err.println(
                        "❌ Telegram getUpdates HTTP " +
                                response.statusCode()
                );

                return;
            }

            processTelegramUpdates(
                    response.body()
            );

        } catch (Exception e) {

            System.err.println(
                    "❌ Telegram polling lỗi: " +
                            e.getMessage()
            );
        }
    }


    // =========================================================
    // TELEGRAM UPDATE PARSER
    // =========================================================

    private static void processTelegramUpdates(
            String json
    ) {

        if (json == null ||
                json.isBlank()) {

            return;
        }

        /*
         * Telegram trả về:
         *
         * {
         *   "ok":true,
         *   "result":[
         *      {
         *          "update_id":123,
         *          ...
         *      }
         *   ]
         * }
         *
         * Ta lấy từng update_id rồi cắt object tương ứng.
         */

        Pattern updatePattern =
                Pattern.compile(
                        "\"update_id\"\\s*:\\s*(\\d+)"
                );

        Matcher matcher =
                updatePattern.matcher(json);

        List<Long> updateIds =
                new ArrayList<>();

        List<Integer> positions =
                new ArrayList<>();

        while (matcher.find()) {

            updateIds.add(
                    Long.parseLong(
                            matcher.group(1)
                    )
            );

            positions.add(
                    matcher.start()
            );
        }

        for (int i = 0;
             i < updateIds.size();
             i++) {

            long updateId =
                    updateIds.get(i);

            int updateIdPosition =
                    positions.get(i);

            int objectStart =
                    json.lastIndexOf(
                            "{",
                            updateIdPosition
                    );

            if (objectStart < 0) {
                continue;
            }

            int objectEnd;

            if (i + 1 < positions.size()) {

                int nextUpdateIdPosition =
                        positions.get(i + 1);

                objectEnd =
                        json.lastIndexOf(
                                "{",
                                nextUpdateIdPosition
                        );

            } else {

                objectEnd =
                        json.lastIndexOf("}");
            }

            if (objectEnd <= objectStart) {
                continue;
            }

            String updateJson =
                    json.substring(
                            objectStart,
                            objectEnd
                    );

            processSingleTelegramUpdate(
                    updateId,
                    updateJson
            );
        }
    }


    // =========================================================
    // PROCESS TELEGRAM UPDATE
    // =========================================================

    private static void processSingleTelegramUpdate(
            long updateId,
            String updateJson
    ) {

        String configuredChatId =
                System.getenv(
                        ENV_CHAT_ID
                );

        if (configuredChatId == null ||
                configuredChatId.isBlank()) {

            return;
        }

        // -----------------------------------------------------
        // Chat ID
        // -----------------------------------------------------

        String chatId =
                extractJsonValue(
                        updateJson,
                        "\"chat\"\\s*:\\s*\\{.*?\"id\"\\s*:\\s*(-?\\d+)"
                );

        if (chatId == null) {

            saveTelegramOffset(updateId);

            return;
        }

        // Chỉ xử lý message trong group đã cấu hình
        if (!configuredChatId.equals(chatId)) {

            saveTelegramOffset(updateId);

            return;
        }

        // -----------------------------------------------------
        // Người gửi
        //
        // from.id = Telegram User ID
        // -----------------------------------------------------

        String userId =
                extractJsonValue(
                        updateJson,
                        "\"from\"\\s*:\\s*\\{.*?\"id\"\\s*:\\s*(\\d+)"
                );

        if (userId == null) {

            saveTelegramOffset(updateId);

            return;
        }

        // -----------------------------------------------------
        // Message text
        // -----------------------------------------------------

        String text =
                extractJsonValue(
                        updateJson,
                        "\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
                );

        if (text == null) {

            saveTelegramOffset(updateId);

            return;
        }

        text =
                unescapeJson(text)
                        .trim();

        System.out.println(
                "📨 Telegram [" +
                        userId +
                        "]: " +
                        text
        );

        // -----------------------------------------------------
        // /start
        // -----------------------------------------------------

        if (text.equalsIgnoreCase(
                "/start"
        )) {

            try {

                sendTelegramMessage(
                        """
                        🤖 IUH EVENT MONITOR

                        Tool đang hoạt động.

                        📢 Bot sẽ gửi thông báo hoạt động mới vào group.

                        🍪 Chỉ admin mới được cập nhật cookie:
                        /cookie YOUR_COOKIE

                        ⏱ Check IUH: mỗi 30 giây
                        💚 Heartbeat: mỗi 30 phút
                        """
                );

            } catch (Exception e) {

                System.err.println(
                        "❌ Không gửi được /start response: " +
                                e.getMessage()
                );
            }
        }

        // -----------------------------------------------------
        // /cookie
        // -----------------------------------------------------

        else if (
                text.equalsIgnoreCase("/cookie")
                        ||
                        text.toLowerCase()
                                .startsWith("/cookie ")
        ) {

            // -------------------------------------------------
            // Kiểm tra quyền
            // -------------------------------------------------

            String adminId =
                    System.getenv(
                            ENV_ADMIN_ID
                    );

            if (adminId == null ||
                    adminId.isBlank()) {

                System.err.println(
                        "⚠️ Chưa cấu hình " +
                                ENV_ADMIN_ID
                );

                try {

                    sendTelegramMessage(
                            "⚠️ Bot chưa cấu hình Telegram Admin ID."
                    );

                } catch (Exception e) {

                    System.err.println(
                            "❌ Không gửi được thông báo: " +
                                    e.getMessage()
                    );
                }

                saveTelegramOffset(updateId);

                return;
            }

            // Không phải admin
            if (!adminId.equals(userId)) {

                System.out.println(
                        "⛔ Từ chối /cookie từ user " +
                                userId
                );

                try {

                    sendTelegramMessage(
                            "⛔ Bạn không có quyền cập nhật cookie IUH."
                    );

                } catch (Exception e) {

                    System.err.println(
                            "❌ Không gửi được thông báo quyền: " +
                                    e.getMessage()
                    );
                }

                saveTelegramOffset(updateId);

                return;
            }

            // -------------------------------------------------
            // Lấy cookie
            // -------------------------------------------------

            if (text.equalsIgnoreCase("/cookie")) {

                try {

                    sendTelegramMessage(
                            """
                            ❌ Cookie không được để trống.

                            Cú pháp:
                            /cookie YOUR_COOKIE
                            """
                    );

                } catch (Exception e) {

                    System.err.println(
                            "❌ Không gửi được hướng dẫn cookie: " +
                                    e.getMessage()
                    );
                }

                saveTelegramOffset(updateId);

                return;
            }

            String newCookie =
                    text.substring(
                            "/cookie ".length()
                    ).trim();

            if (newCookie.isBlank()) {

                saveTelegramOffset(updateId);

                return;
            }

            // -------------------------------------------------
            // Lưu cookie mới
            // -------------------------------------------------

            saveCookie(newCookie);

            cookieExpiredNotified = false;

            try {

                sendTelegramMessage(
                        """
                        ✅ Đã cập nhật cookie IUH.

                        🔄 Tool sẽ tiếp tục kiểm tra IUH.
                        ⏱ Chu kỳ kiểm tra: 30 giây.
                        """
                );

            } catch (Exception e) {

                System.err.println(
                        "❌ Không gửi được xác nhận cookie: " +
                                e.getMessage()
                );
            }
        }

        // -----------------------------------------------------
        // Save offset
        // -----------------------------------------------------

        saveTelegramOffset(updateId);
    }


    // =========================================================
    // JSON VALUE
    // =========================================================

    private static String extractJsonValue(
            String json,
            String regex
    ) {

        Pattern pattern =
                Pattern.compile(
                        regex,
                        Pattern.DOTALL
                );

        Matcher matcher =
                pattern.matcher(json);

        if (matcher.find()) {

            return matcher.group(1);
        }

        return null;
    }


    // =========================================================
    // JSON UNESCAPE
    // =========================================================

    private static String unescapeJson(
            String text
    ) {

        return text
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }


    // =========================================================
    // TELEGRAM OFFSET
    // =========================================================

    private static long loadTelegramOffset() {

        if (!Files.exists(
                TELEGRAM_OFFSET_FILE
        )) {

            return 0;
        }

        try {

            String value =
                    Files.readString(
                            TELEGRAM_OFFSET_FILE
                    ).trim();

            if (value.isBlank()) {

                return 0;
            }

            return Long.parseLong(value);

        } catch (Exception e) {

            return 0;
        }
    }


    private static synchronized void saveTelegramOffset(
            long offset
    ) {

        try {

            Files.writeString(
                    TELEGRAM_OFFSET_FILE,
                    String.valueOf(offset),
                    StandardCharsets.UTF_8
            );

        } catch (IOException e) {

            System.err.println(
                    "❌ Không thể lưu Telegram offset: " +
                            e.getMessage()
            );
        }
    }


    // =========================================================
    // ACTIVITY CLASS
    // =========================================================

    private static class Activity {

        String id;
        String name;
        String semester;
        String points;
        String endDate;

        int registeredSlots;
        int totalSlots;
        int remainingSlots;

        String registerStatus;
        boolean canRegister;

        String detail;


        Activity(
                String id,
                String name,
                String semester,
                String points,
                String endDate,
                int registeredSlots,
                int totalSlots,
                int remainingSlots,
                String registerStatus,
                boolean canRegister,
                String detail
        ) {

            this.id = id;
            this.name = name;
            this.semester = semester;
            this.points = points;
            this.endDate = endDate;

            this.registeredSlots =
                    registeredSlots;

            this.totalSlots =
                    totalSlots;

            this.remainingSlots =
                    remainingSlots;

            this.registerStatus =
                    registerStatus;

            this.canRegister =
                    canRegister;

            this.detail =
                    detail;
        }


        // -----------------------------------------------------
        // Save state
        // -----------------------------------------------------

        String toLine() {

            return String.join(
                    "|",
                    escape(id),
                    escape(name),
                    escape(semester),
                    escape(points),
                    escape(endDate),
                    String.valueOf(
                            registeredSlots
                    ),
                    String.valueOf(
                            totalSlots
                    ),
                    String.valueOf(
                            remainingSlots
                    ),
                    escape(registerStatus),
                    String.valueOf(
                            canRegister
                    ),
                    escape(detail)
            );
        }


        // -----------------------------------------------------
        // Load state
        // -----------------------------------------------------

        static Activity fromLine(
                String line
        ) {

            try {

                String[] parts =
                        line.split(
                                "\\|",
                                -1
                        );

                if (parts.length < 11) {

                    return null;
                }

                return new Activity(
                        unescape(parts[0]),
                        unescape(parts[1]),
                        unescape(parts[2]),
                        unescape(parts[3]),
                        unescape(parts[4]),
                        Integer.parseInt(
                                parts[5]
                        ),
                        Integer.parseInt(
                                parts[6]
                        ),
                        Integer.parseInt(
                                parts[7]
                        ),
                        unescape(parts[8]),
                        Boolean.parseBoolean(
                                parts[9]
                        ),
                        unescape(parts[10])
                );

            } catch (Exception e) {

                return null;
            }
        }


        private static String escape(
                String value
        ) {

            if (value == null) {

                return "";
            }

            return value
                    .replace("\\", "\\\\")
                    .replace("|", "\\|")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
        }


        private static String unescape(
                String value
        ) {

            if (value == null) {

                return "";
            }

            return value
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\|", "|")
                    .replace("\\\\", "\\");
        }
    }


    // =========================================================
    // SLOT INFO
    // =========================================================

    private static class SlotInfo {

        int registeredSlots;
        int totalSlots;
        int remainingSlots;


        SlotInfo(
                int registeredSlots,
                int totalSlots,
                int remainingSlots
        ) {

            this.registeredSlots =
                    registeredSlots;

            this.totalSlots =
                    totalSlots;

            this.remainingSlots =
                    remainingSlots;
        }
    }
}