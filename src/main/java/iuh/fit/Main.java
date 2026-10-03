package iuh.fit;

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
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /*
     * Cookie IUH được lưu ở file này.
     */
    private static final Path COOKIE_FILE =
            Paths.get("iuh_cookie.txt");

    /*
     * Lưu danh sách activity đã biết.
     */
    private static final Path ACTIVITIES_FILE =
            Paths.get("activities.txt");

    /*
     * Lưu Telegram update_id cuối cùng đã xử lý.
     */
    private static final Path TELEGRAM_OFFSET_FILE =
            Paths.get("telegram_offset.txt");

    /*
     * Biến môi trường.
     */
    private static final String ENV_IUH_COOKIE =
            "IUH_COOKIE";

    private static final String ENV_BOT_TOKEN =
            "TELEGRAM_BOT_TOKEN";

    private static final String ENV_CHAT_ID =
            "TELEGRAM_CHAT_ID";

    /*
     * HTTP client dùng chung.
     */
    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

    /*
     * Tránh gửi thông báo cookie hết hạn nhiều lần.
     */
    private static volatile boolean cookieExpiredNotified =
            false;


    // =========================================================
    // MAIN
    // =========================================================

    public static void main(String[] args) {

        System.out.println("========================================");
        System.out.println("       IUH EVENT MONITOR");
        System.out.println("========================================");
        System.out.println("IUH      : mỗi 1 phút");
        System.out.println("Telegram : mỗi 5 giây");
        System.out.println("========================================");

        initializeCookie();

        ScheduledExecutorService scheduler =
                Executors.newScheduledThreadPool(2);

        /*
         * Check IUH mỗi 1 phút.
         */
        scheduler.scheduleAtFixedRate(
                () -> {

                    try {
                        checkActivities();

                    } catch (Exception e) {

                        System.err.println(
                                "❌ Lỗi check IUH: "
                                        + e.getMessage()
                        );
                    }

                },
                0,
                30,
                TimeUnit.SECONDS
        );

        /*
         * Check Telegram mỗi 5 giây.
         */
        scheduler.scheduleAtFixedRate(
                () -> {

                    try {
                        checkTelegramMessages();

                    } catch (Exception e) {

                        System.err.println(
                                "❌ Lỗi Telegram: "
                                        + e.getMessage()
                        );
                    }

                },
                0,
                5,
                TimeUnit.SECONDS
        );

        System.out.println("🚀 Tool đang chạy...");
    }


    // =========================================================
    // COOKIE
    // =========================================================

    /**
     * Khởi tạo cookie.
     *
     * Ưu tiên:
     *
     * 1. iuh_cookie.txt
     * 2. IUH_COOKIE environment variable
     */
    private static void initializeCookie() {

        try {

            String cookie = loadCookie();

            if (cookie != null && !cookie.isBlank()) {

                System.out.println(
                        "🍪 Đã load cookie IUH."
                );

                return;
            }

            /*
             * Nếu chưa có file thì thử environment variable.
             */
            String envCookie =
                    System.getenv(ENV_IUH_COOKIE);

            if (envCookie != null
                    && !envCookie.isBlank()) {

                saveCookie(envCookie);

                System.out.println(
                        "🍪 Đã lấy cookie từ IUH_COOKIE."
                );

                return;
            }

            System.out.println(
                    "⚠️ Chưa có cookie IUH."
            );

            System.out.println(
                    "Hãy gửi cookie cho Telegram bằng:"
            );

            System.out.println(
                    "/cookie YOUR_COOKIE"
            );

        } catch (Exception e) {

            System.err.println(
                    "❌ Không thể khởi tạo cookie: "
                            + e.getMessage()
            );
        }
    }


    /**
     * Đọc cookie từ file.
     */
    private static String loadCookie()
            throws IOException {

        if (!Files.exists(COOKIE_FILE)) {
            return null;
        }

        return Files.readString(
                COOKIE_FILE,
                StandardCharsets.UTF_8
        ).trim();
    }


    /**
     * Lưu cookie.
     */
    private static void saveCookie(
            String cookie)
            throws IOException {

        Files.writeString(
                COOKIE_FILE,
                cookie.trim(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        /*
         * Cookie mới => có thể kiểm tra lại bình thường.
         */
        cookieExpiredNotified = false;
    }


    // =========================================================
    // CHECK IUH
    // =========================================================

    /**
     * Kiểm tra hoạt động trên IUH.
     */
    private static void checkActivities()
            throws Exception {

        String cookie = loadCookie();

        if (cookie == null || cookie.isBlank()) {

            System.out.println(
                    "⚠️ Chưa có cookie IUH."
            );

            return;
        }

        System.out.println(
                "\n[" + java.time.LocalDateTime.now()
                        + "] Checking IUH..."
        );

        /*
         * Request IUH.
         */
        Document doc =
                Jsoup.connect(IUH_URL)
                        .header(
                                "Cookie",
                                cookie
                        )
                        .userAgent(
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                        + "AppleWebKit/537.36 "
                                        + "(KHTML, like Gecko) "
                                        + "Chrome/154.0.0.0 Safari/537.36"
                        )
                        .timeout(20_000)
                        .followRedirects(true)
                        .get();

        /*
         * Kiểm tra cookie/session.
         */
        if (isLoginPage(doc)) {

            handleCookieExpired();

            return;
        }

        /*
         * Parse activity.
         */
        Map<String, Activity> currentActivities =
                parseActivities(doc);

        if (currentActivities.isEmpty()) {

            System.err.println(
                    "⚠️ Không tìm thấy hoạt động."
            );

            return;
        }

        System.out.println(
                "📋 Tìm thấy "
                        + currentActivities.size()
                        + " hoạt động."
        );

        /*
         * Load trạng thái cũ.
         */
        Map<String, Activity> oldActivities =
                loadActivities();

        /*
         * =====================================================
         * FIRST RUN
         * =====================================================
         *
         * Không gửi Telegram.
         * Chỉ lưu baseline.
         */
        if (!Files.exists(ACTIVITIES_FILE)) {

            saveActivities(currentActivities);

            System.out.println(
                    "📌 Đã lưu baseline "
                            + currentActivities.size()
                            + " hoạt động."
            );

            return;
        }

        /*
         * Danh sách hoạt động mới.
         */
        List<Activity> newActivities =
                new ArrayList<>();

        /*
         * =====================================================
         * DETECT NEW ACTIVITY
         * =====================================================
         */
        for (Activity current :
                currentActivities.values()) {

            Activity old =
                    oldActivities.get(current.id);

            /*
             * Chỉ có activity mới mới được notify.
             */
            if (old == null) {

                System.out.println(
                        "🆕 Activity mới: "
                                + current.name
                );

                /*
                 * QUAN TRỌNG:
                 *
                 * Không kiểm tra remainingSlots ở đây.
                 *
                 * Dù:
                 *
                 * 400/400
                 *
                 * vẫn notify.
                 */
                newActivities.add(current);
            }
        }

        /*
         * =====================================================
         * SEND TELEGRAM
         * =====================================================
         */
        if (!newActivities.isEmpty()) {

            sendTelegram(newActivities);

        } else {

            System.out.println(
                    "✓ Không có hoạt động mới."
            );
        }

        /*
         * Cập nhật baseline.
         */
        saveActivities(currentActivities);
    }


    // =========================================================
    // PARSE ACTIVITIES
    // =========================================================

    /**
     * Parse danh sách hoạt động từ HTML IUH.
     */
    private static Map<String, Activity> parseActivities(
            Document doc) {

        Map<String, Activity> activities =
                new LinkedHashMap<>();

        /*
         * Tìm đúng table.
         */
        for (Element table : doc.select("table")) {

            String tableText =
                    table.text();

            /*
             * Kiểm tra đây có phải bảng hoạt động không.
             */
            if (!tableText.contains(
                    "Hoạt động(T/gia)")) {

                continue;
            }

            if (!tableText.contains(
                    "Kết thúc đăng ký")) {

                continue;
            }

            /*
             * Duyệt các row.
             *
             * Dùng tbody tr thay vì tbody > tr
             * vì HTML IUH có modal xen giữa các row.
             */
            for (Element row :
                    table.select("tbody tr")) {

                Elements cells =
                        row.select("> td");

                /*
                 * Cấu trúc:
                 *
                 * 0 = TT
                 * 1 = Hoạt động
                 * 2 = Học kỳ
                 * 3 = Điểm
                 * 4 = Kết thúc đăng ký
                 * 5 = Đăng ký
                 */
                if (cells.size() < 6) {
                    continue;
                }

                /*
                 * =================================================
                 * NAME
                 * =================================================
                 */
                Element activityCell =
                        cells.get(1);

                Element activityLink =
                        activityCell.selectFirst("a");

                if (activityLink == null) {
                    continue;
                }

                String rawName =
                        activityLink.text().trim();

                if (rawName.isBlank()) {
                    continue;
                }

                /*
                 * =================================================
                 * ACTIVITY ID
                 * =================================================
                 *
                 * data-target="#myModal_4160"
                 *
                 * => 4160
                 */
                String target =
                        activityLink.attr(
                                "data-target"
                        );

                String activityId =
                        extractActivityId(target);

                if (activityId == null) {

                    System.err.println(
                            "⚠️ Không lấy được ID: "
                                    + rawName
                    );

                    continue;
                }

                /*
                 * =================================================
                 * SLOT
                 * =================================================
                 */
                SlotInfo slotInfo =
                        parseSlot(rawName);

                /*
                 * Xóa "(400/400)" khỏi tên.
                 */
                String cleanName =
                        cleanActivityName(rawName);

                /*
                 * =================================================
                 * SEMESTER
                 * =================================================
                 */
                String semester =
                        cells.get(2)
                                .text()
                                .trim();

                /*
                 * =================================================
                 * POINTS
                 * =================================================
                 */
                String points =
                        cells.get(3)
                                .text()
                                .trim();

                /*
                 * =================================================
                 * END DATE
                 * =================================================
                 */
                String endDate =
                        cells.get(4)
                                .text()
                                .trim();

                /*
                 * =================================================
                 * REGISTER STATUS
                 * =================================================
                 */
                String registerStatus =
                        cells.get(5)
                                .text()
                                .trim();

                /*
                 * =================================================
                 * CAN REGISTER
                 * =================================================
                 */
                boolean canRegister =
                        slotInfo.remainingSlots > 0
                                && !registerStatus
                                .toLowerCase()
                                .contains("đã đủ");

                /*
                 * =================================================
                 * DETAIL
                 * =================================================
                 */
                String detail =
                        parseDetail(
                                doc,
                                activityId
                        );

                /*
                 * =================================================
                 * REGISTER URL
                 * =================================================
                 */
                String registerUrl =
                        parseRegisterUrl(
                                cells.get(5)
                        );

                /*
                 * =================================================
                 * CREATE ACTIVITY
                 * =================================================
                 */
                Activity activity =
                        new Activity(
                                activityId,
                                cleanName,
                                semester,
                                points,
                                endDate,
                                slotInfo.registeredSlots,
                                slotInfo.totalSlots,
                                slotInfo.remainingSlots,
                                registerStatus,
                                canRegister,
                                detail,
                                registerUrl
                        );

                activities.put(
                        activityId,
                        activity
                );
            }

            /*
             * Đã tìm đúng table.
             */
            break;
        }

        return activities;
    }


    // =========================================================
    // ACTIVITY ID
    // =========================================================

    /**
     * Lấy ID từ:
     *
     * #myModal_4160
     *
     * => 4160
     */
    private static String extractActivityId(
            String target) {

        if (target == null
                || target.isBlank()) {

            return null;
        }

        String prefix =
                "#myModal_";

        if (!target.startsWith(prefix)) {
            return null;
        }

        return target.substring(
                prefix.length()
        );
    }


    // =========================================================
    // SLOT
    // =========================================================

    /**
     * Parse:
     *
     * Event ABC (400/400)
     *
     * registered = 400
     * total      = 400
     * remaining  = 0
     */
    private static SlotInfo parseSlot(
            String name) {

        int open =
                name.lastIndexOf("(");

        int close =
                name.lastIndexOf(")");

        if (open == -1
                || close == -1
                || close <= open) {

            return new SlotInfo(
                    0,
                    0,
                    0
            );
        }

        String slotText =
                name.substring(
                        open + 1,
                        close
                ).trim();

        String[] parts =
                slotText.split("/");

        if (parts.length != 2) {

            return new SlotInfo(
                    0,
                    0,
                    0
            );
        }

        try {

            int registered =
                    Integer.parseInt(
                            parts[0].trim()
                    );

            int total =
                    Integer.parseInt(
                            parts[1].trim()
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

        } catch (NumberFormatException e) {

            return new SlotInfo(
                    0,
                    0,
                    0
            );
        }
    }


    /**
     * Xóa "(400/400)" khỏi tên.
     */
    private static String cleanActivityName(
            String name) {

        return name
                .replaceFirst(
                        "\\s*\\(\\d+\\s*/\\s*\\d+\\)\\s*$",
                        ""
                )
                .trim();
    }


    // =========================================================
    // DETAIL
    // =========================================================

    /**
     * Lấy thông tin từ modal.
     *
     * Ví dụ:
     *
     * #myModal_4160
     */
    private static String parseDetail(
            Document doc,
            String activityId) {

        Element modal =
                doc.selectFirst(
                        "#myModal_" + activityId
                );

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

        /*
         * Giữ <br> thành xuống dòng.
         */
        String detail =
                body.html()
                        .replaceAll(
                                "(?i)<br\\s*/?>",
                                "\n"
                        )
                        .replaceAll(
                                "<[^>]*>",
                                ""
                        )
                        .replace(
                                "&nbsp;",
                                " "
                        )
                        .trim();

        return detail;
    }


    // =========================================================
    // REGISTER URL
    // =========================================================

    private static String parseRegisterUrl(
            Element registerCell) {

        Element link =
                registerCell.selectFirst(
                        "a[href]"
                );

        if (link == null) {
            return "";
        }

        String href =
                link.attr("href")
                        .trim();

        if (href.isBlank()
                || href.equals("#")) {

            return "";
        }

        try {

            return URI.create(IUH_URL)
                    .resolve(href)
                    .toString();

        } catch (Exception e) {

            return href;
        }
    }


    // =========================================================
    // LOGIN / COOKIE EXPIRED
    // =========================================================

    /**
     * Kiểm tra IUH có trả về trang login không.
     */
    private static boolean isLoginPage(
            Document doc) {

        /*
         * Meta refresh.
         */
        String refresh =
                doc.select(
                        "meta[http-equiv=refresh]"
                ).attr("content");

        if (refresh != null
                && refresh
                .toLowerCase()
                .contains("login")) {

            return true;
        }

        /*
         * Title.
         */
        String title =
                doc.title()
                        .toLowerCase();

        if (title.contains("login")
                || title.contains("đăng nhập")) {

            return true;
        }

        /*
         * Kiểm tra form login.
         */
        Element passwordInput =
                doc.selectFirst(
                        "input[type=password]"
                );

        if (passwordInput != null) {
            return true;
        }

        /*
         * Một số trường hợp IUH có text đăng nhập
         * nhưng title không đổi.
         */
        String bodyText =
                doc.body() != null
                        ? doc.body().text()
                        : "";

        String lowerBody =
                bodyText.toLowerCase();

        return lowerBody.contains("đăng nhập")
                && lowerBody.contains("mật khẩu");
    }


    /**
     * Xử lý cookie hết hạn.
     */
    private static void handleCookieExpired()
            throws Exception {

        System.err.println(
                "🍪❌ Cookie IUH đã hết hạn."
        );

        /*
         * Không spam Telegram.
         */
        if (cookieExpiredNotified) {
            return;
        }

        cookieExpiredNotified = true;

        sendTelegramMessage(
                "🍪❌ COOKIE IUH ĐÃ HẾT HẠN!\n\n"
                        + "Hãy gửi cookie mới cho bot bằng:\n"
                        + "/cookie YOUR_COOKIE"
        );
    }


    // =========================================================
    // LOAD ACTIVITIES
    // =========================================================

    private static Map<String, Activity>
    loadActivities()
            throws IOException {

        Map<String, Activity> result =
                new LinkedHashMap<>();

        if (!Files.exists(ACTIVITIES_FILE)) {
            return result;
        }

        List<String> lines =
                Files.readAllLines(
                        ACTIVITIES_FILE,
                        StandardCharsets.UTF_8
                );

        for (String line : lines) {

            if (line.isBlank()) {
                continue;
            }

            String[] parts =
                    line.split(
                            "\\|",
                            -1
                    );

            /*
             * Format:
             *
             * 0  ID
             * 1  name
             * 2  semester
             * 3  points
             * 4  endDate
             * 5  registered
             * 6  total
             * 7  remaining
             * 8  status
             * 9  canRegister
             * 10 detail
             * 11 registerUrl
             */
            if (parts.length < 12) {
                continue;
            }

            try {

                Activity activity =
                        new Activity(
                                parts[0],
                                parts[1],
                                parts[2],
                                parts[3],
                                parts[4],
                                Integer.parseInt(parts[5]),
                                Integer.parseInt(parts[6]),
                                Integer.parseInt(parts[7]),
                                parts[8],
                                Boolean.parseBoolean(
                                        parts[9]
                                ),
                                parts[10].replace(
                                        "\\n",
                                        "\n"
                                ),
                                parts[11]
                        );

                result.put(
                        activity.id,
                        activity
                );

            } catch (Exception e) {

                System.err.println(
                        "⚠️ Không thể đọc activity: "
                                + line
                );
            }
        }

        return result;
    }


    // =========================================================
    // SAVE ACTIVITIES
    // =========================================================

    private static void saveActivities(
            Map<String, Activity> activities)
            throws IOException {

        List<String> lines =
                new ArrayList<>();

        for (Activity activity :
                activities.values()) {

            String name =
                    sanitize(
                            activity.name
                    );

            String semester =
                    sanitize(
                            activity.semester
                    );

            String points =
                    sanitize(
                            activity.points
                    );

            String endDate =
                    sanitize(
                            activity.endDate
                    );

            String status =
                    sanitize(
                            activity.registerStatus
                    );

            String detail =
                    activity.detail
                            .replace(
                                    "\n",
                                    "\\n"
                            )
                            .replace(
                                    "|",
                                    "/"
                            );

            String registerUrl =
                    sanitize(
                            activity.registerUrl
                    );

            String line =
                    String.join(
                            "|",
                            activity.id,
                            name,
                            semester,
                            points,
                            endDate,
                            String.valueOf(
                                    activity.registeredSlots
                            ),
                            String.valueOf(
                                    activity.totalSlots
                            ),
                            String.valueOf(
                                    activity.remainingSlots
                            ),
                            status,
                            String.valueOf(
                                    activity.canRegister
                            ),
                            detail,
                            registerUrl
                    );

            lines.add(line);
        }

        Files.write(
                ACTIVITIES_FILE,
                lines,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }


    /**
     * Loại ký tự làm hỏng format file.
     */
    private static String sanitize(
            String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace(
                        "|",
                        "/"
                )
                .replace(
                        "\r",
                        " "
                )
                .replace(
                        "\n",
                        " "
                );
    }

    private static String extractTime(String detail) {
        if (detail == null || detail.isBlank()) {
            return "";
        }

        for (String line : detail.split("\\R")) {
            String cleaned = line.trim();

            if (cleaned.startsWith("- Thời gian:")) {
                return cleaned.substring("- Thời gian:".length()).trim();
            }

            if (cleaned.startsWith("Thời gian:")) {
                return cleaned.substring("Thời gian:".length()).trim();
            }
        }

        return "";
    }


    // =========================================================
    // TELEGRAM SEND
    // =========================================================

    /**
     * Gửi danh sách activity mới.
     */
    private static void sendTelegram(List<Activity> activities) throws Exception {
        StringBuilder message = new StringBuilder();

        message.append("🆕 IUH CÓ HOẠT ĐỘNG MỚI!\n");

        for (Activity activity : activities) {
            message.append("\n");

            message.append("📌 ").append(activity.name).append("\n");

            if (activity.remainingSlots > 0) {
                message.append("🟢 CÒN SLOT\n");
            } else {
                message.append("🔴 HẾT SLOT\n");
            }

            message.append("⭐️ Điểm: ").append(activity.points).append("\n");
            message.append("👥 Slot: ")
                    .append(activity.registeredSlots)
                    .append("/")
                    .append(activity.totalSlots)
                    .append("\n");

            message.append("📌 Trạng thái: ")
                    .append(activity.registerStatus)
                    .append("\n");

            message.append("⏰ Kết thúc đăng ký: ")
                    .append(activity.endDate)
                    .append("\n");

            String time = extractTime(activity.detail);

            if (!time.isBlank()) {
                message.append("📋 Thời gian: ")
                        .append(time)
                        .append("\n");
            }
        }

        sendTelegramMessage(message.toString());
    }


    /**
     * Gửi một message Telegram.
     */
    private static void sendTelegramMessage(
            String message)
            throws Exception {

        String botToken =
                System.getenv(
                        ENV_BOT_TOKEN
                );

        String chatId =
                System.getenv(
                        ENV_CHAT_ID
                );

        if (botToken == null
                || botToken.isBlank()) {

            throw new IllegalStateException(
                    "Thiếu TELEGRAM_BOT_TOKEN"
            );
        }

        if (chatId == null
                || chatId.isBlank()) {

            throw new IllegalStateException(
                    "Thiếu TELEGRAM_CHAT_ID"
            );
        }

        String encodedMessage =
                URLEncoder.encode(
                        message,
                        StandardCharsets.UTF_8
                );

        String encodedChatId =
                URLEncoder.encode(
                        chatId,
                        StandardCharsets.UTF_8
                );

        String telegramUrl =
                "https://api.telegram.org/bot"
                        + botToken
                        + "/sendMessage"
                        + "?chat_id="
                        + encodedChatId
                        + "&text="
                        + encodedMessage;

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        telegramUrl
                                )
                        )
                        .timeout(
                                Duration.ofSeconds(10)
                        )
                        .GET()
                        .build();

        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers
                                .ofString()
                );

        if (response.statusCode() != 200) {

            throw new IllegalStateException(
                    "Telegram API lỗi: HTTP "
                            + response.statusCode()
                            + "\n"
                            + response.body()
            );
        }

        System.out.println(
                "✅ Đã gửi Telegram."
        );
    }


    // =========================================================
    // TELEGRAM RECEIVE
    // =========================================================

    /**
     * Lấy message mới từ Telegram.
     */
    private static void checkTelegramMessages()
            throws Exception {

        String botToken =
                System.getenv(
                        ENV_BOT_TOKEN
                );

        String allowedChatId =
                System.getenv(
                        ENV_CHAT_ID
                );

        if (botToken == null
                || botToken.isBlank()) {

            return;
        }

        if (allowedChatId == null
                || allowedChatId.isBlank()) {

            return;
        }

        long offset =
                loadTelegramOffset();

        String url =
                "https://api.telegram.org/bot"
                        + botToken
                        + "/getUpdates"
                        + "?timeout=0"
                        + "&offset="
                        + (offset + 1);

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(url)
                        )
                        .timeout(
                                Duration.ofSeconds(10)
                        )
                        .GET()
                        .build();

        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers
                                .ofString()
                );

        if (response.statusCode() != 200) {

            System.err.println(
                    "❌ Telegram getUpdates lỗi: "
                            + response.statusCode()
            );

            return;
        }

        processTelegramJson(
                response.body(),
                allowedChatId
        );
    }


    // =========================================================
    // TELEGRAM JSON PARSER
    // =========================================================

    /**
     * Parse Telegram JSON.
     *
     * Không dùng thêm JSON dependency.
     */
    private static void processTelegramJson(
            String json,
            String allowedChatId)
            throws Exception {

        if (json == null
                || json.isBlank()) {

            return;
        }

        /*
         * Telegram trả:
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
         */

        Pattern updatePattern =
                Pattern.compile(
                        "\"update_id\"\\s*:\\s*(\\d+)"
                );

        Matcher updateMatcher =
                updatePattern.matcher(json);

        List<Integer> updatePositions =
                new ArrayList<>();

        List<Long> updateIds =
                new ArrayList<>();

        while (updateMatcher.find()) {

            updatePositions.add(
                    updateMatcher.start()
            );

            updateIds.add(
                    Long.parseLong(
                            updateMatcher.group(1)
                    )
            );
        }

        if (updateIds.isEmpty()) {
            return;
        }

        for (int i = 0;
             i < updateIds.size();
             i++) {

            long updateId =
                    updateIds.get(i);

            int start =
                    updatePositions.get(i);

            int end;

            if (i + 1 < updatePositions.size()) {

                end =
                        updatePositions.get(
                                i + 1
                        );

            } else {

                end = json.length();
            }

            String updateJson =
                    json.substring(
                            start,
                            end
                    );

            processSingleTelegramUpdate(
                    updateId,
                    updateJson,
                    allowedChatId
            );

            /*
             * Lưu offset.
             */
            saveTelegramOffset(
                    updateId
            );
        }
    }


    /**
     * Xử lý một Telegram update.
     */
    private static void processSingleTelegramUpdate(
            long updateId,
            String updateJson,
            String allowedChatId)
            throws Exception {

        /*
         * Lấy chat.id.
         */
        Pattern chatPattern =
                Pattern.compile(
                        "\"chat\"\\s*:\\s*\\{[^}]*?\"id\"\\s*:\\s*(-?\\d+)"
                );

        Matcher chatMatcher =
                chatPattern.matcher(updateJson);

        if (!chatMatcher.find()) {
            return;
        }

        String senderChatId =
                chatMatcher.group(1);

        /*
         * Chỉ nhận message từ chat được phép.
         */
        if (!allowedChatId.equals(
                senderChatId)) {

            System.out.println(
                    "⚠️ Bỏ qua message từ chat: "
                            + senderChatId
            );

            return;
        }

        /*
         * Lấy text.
         */
        Pattern textPattern =
                Pattern.compile(
                        "\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
                );

        Matcher textMatcher =
                textPattern.matcher(updateJson);

        if (!textMatcher.find()) {
            return;
        }

        String text =
                unescapeJson(
                        textMatcher.group(1)
                );

        if (text == null) {
            return;
        }

        text = text.trim();

        System.out.println(
                "📩 Telegram: " + text
        );

        /*
         * =============================================
         * /start
         * =============================================
         */

        if (text.equalsIgnoreCase(
                "/start")) {

            sendTelegramMessage(
                    "🤖 IUH Event Monitor đang chạy.\n\n"
                            + "Khi cookie IUH hết hạn, hãy gửi:\n"
                            + "/cookie YOUR_COOKIE"
            );

            return;
        }

        /*
         * =============================================
         * /cookie
         * =============================================
         */

        if (text.startsWith(
                "/cookie")) {

            String cookie =
                    text.substring(
                            "/cookie".length()
                    ).trim();

            if (cookie.isBlank()) {

                sendTelegramMessage(
                        "❌ Cookie trống.\n\n"
                                + "Dùng:\n"
                                + "/cookie YOUR_COOKIE"
                );

                return;
            }

            /*
             * Lưu cookie mới.
             */
            saveCookie(cookie);

            /*
             * Reset trạng thái expired.
             */
            cookieExpiredNotified = false;

            sendTelegramMessage(
                    "✅ Đã cập nhật cookie IUH.\n\n"
                            + "Tool sẽ sử dụng cookie mới "
                            + "ở lần kiểm tra tiếp theo."
            );

            System.out.println(
                    "🍪 Đã nhận cookie mới từ Telegram."
            );
        }
    }


    // =========================================================
    // JSON UNESCAPE
    // =========================================================

    /**
     * Unescape chuỗi JSON cơ bản.
     */
    private static String unescapeJson(
            String value) {

        if (value == null) {
            return null;
        }

        return value
                .replace(
                        "\\\"",
                        "\""
                )
                .replace(
                        "\\\\",
                        "\\"
                )
                .replace(
                        "\\/",
                        "/"
                )
                .replace(
                        "\\n",
                        "\n"
                )
                .replace(
                        "\\r",
                        "\r"
                )
                .replace(
                        "\\t",
                        "\t"
                );
    }


    // =========================================================
    // TELEGRAM OFFSET
    // =========================================================

    /**
     * Đọc update_id cuối cùng.
     */
    private static long loadTelegramOffset()
            throws IOException {

        if (!Files.exists(
                TELEGRAM_OFFSET_FILE)) {

            return 0;
        }

        String value =
                Files.readString(
                        TELEGRAM_OFFSET_FILE,
                        StandardCharsets.UTF_8
                ).trim();

        if (value.isBlank()) {
            return 0;
        }

        try {

            return Long.parseLong(value);

        } catch (NumberFormatException e) {

            return 0;
        }
    }


    /**
     * Lưu update_id.
     */
    private static void saveTelegramOffset(
            long updateId)
            throws IOException {

        Files.writeString(
                TELEGRAM_OFFSET_FILE,
                String.valueOf(updateId),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
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
        String registerUrl;


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
                String detail,
                String registerUrl) {

            this.id =
                    id;

            this.name =
                    name;

            this.semester =
                    semester;

            this.points =
                    points;

            this.endDate =
                    endDate;

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

            this.registerUrl =
                    registerUrl;
        }
    }


    // =========================================================
    // SLOT INFO CLASS
    // =========================================================

    private static class SlotInfo {

        int registeredSlots;
        int totalSlots;
        int remainingSlots;


        SlotInfo(
                int registeredSlots,
                int totalSlots,
                int remainingSlots) {

            this.registeredSlots =
                    registeredSlots;

            this.totalSlots =
                    totalSlots;

            this.remainingSlots =
                    remainingSlots;
        }
    }
}