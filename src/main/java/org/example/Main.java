package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class Main {
    // -------------------------
    // CONFIGURATION
    // -------------------------
    private static final String CLIENT_ID = "YOUR_CLIENT_ID";
    private static final String CLIENT_SECRET = "YOUR_CLIENT_SECRET_FROM_STRAVA";
    private static final String REDIRECT_URI = "http://localhost:8000/callback";
    private static final String AUTH_URL = "https://www.strava.com/oauth/authorize";
    private static final String TOKEN_URL = "https://www.strava.com/oauth/token";

//    These are the permissions required to get activities
    private static final String SCOPE = "read,activity:read";

    private static String ACCESS_TOKEN;
    private static final String STRAVA_API_URL = "https://www.strava.com/api/v3/athlete/activities";
    private static String authorizationCode;
    private static String YOUR_DESIRED_FILE_LOCATION; // something like = "	C:\Users\BobSmith\Downloads\"



    public static void main(String[] args) throws Exception {

        // Start callback server
        startCallbackServer();

        // Build authorization URL
        String authRequestUrl = AUTH_URL +
                "?client_id=" + CLIENT_ID +
                "&response_type=code" +
                "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8) +
                "&scope=" + SCOPE;

        System.out.println("Opening browser for Strava authorization...");
        Desktop.getDesktop().browse(new URI(authRequestUrl));

        // Wait for authorization code
        while (authorizationCode == null) {
            Thread.sleep(500);
        }



        System.out.println("Authorization code received: " + authorizationCode);

        // Exchange code for access token
        String jsonResponse = exchangeCodeForToken(authorizationCode);

        ACCESS_TOKEN = extractAccessToken(jsonResponse);

        // Calculate start of today (Unix timestamp)
        long startOfToday = LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault())
                .toEpochSecond();



        // Build request URL
        String requestUrl = STRAVA_API_URL +
                "?after=" + startOfToday +
                "&per_page=50";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .header("Authorization", "Bearer " + ACCESS_TOKEN)
                .GET()
                .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            System.out.println("Today's activities:");
            System.out.println(response.body());
        } else {
            System.out.println("Request failed");
            System.out.println("Status: " + response.statusCode());
            System.out.println(response.body());
        }

        String apiResponse = response.body();

//        Arguments are: the JSON provided by the strava API response and the desired file path and filename for the CSV
        writeActivitiesCsv(apiResponse, YOUR_DESIRED_FILE_LOCATION + LocalDate.now());

    }

    // -------------------------
    // CALLBACK SERVER
    // -------------------------
    private static void startCallbackServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.createContext("/callback", new CallbackHandler());
        server.setExecutor(null);
        server.start();

        System.out.println("Listening on http://localhost:8000/callback");
    }

    static class CallbackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQueryParams(query);

            authorizationCode = params.get("code");

            String response = "Authorization successful! You may return to the application.";
            exchange.sendResponseHeaders(200, response.length());

            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    // -------------------------
    // TOKEN EXCHANGE
    // -------------------------
    private static String exchangeCodeForToken(String code) throws Exception {
        String formData = "client_id=" + CLIENT_ID +
                "&client_secret=" + CLIENT_SECRET +
                "&code=" + code +
                "&grant_type=authorization_code";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());


        return response.body();


    }

    // -------------------------
    // QUERY PARAM PARSER
    // -------------------------
    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;

        for (String pair : query.split("&")) {
            String[] parts = pair.split("=");
            if (parts.length == 2) {
                params.put(parts[0], parts[1]);
            }
        }
        return params;
    }



   public static String extractAccessToken(String json) throws Exception {
       ObjectMapper mapper = new ObjectMapper();
       JsonNode root = mapper.readTree(json);
       return root.get("access_token").asText();
   }

    public static void writeActivitiesCsv(String json, String outputFile) throws Exception {

        // CSV header (must be first row)
        String header = String.join(",",
                "Activity Date",
                "Elevation Gain",
                "Activity Time",
                "Activity Type",
                "Comment",
                "Distance (Miles)"
        );

        if(json.equalsIgnoreCase("[]")){
            System.out.println("This will fail because it is an empty array");
            return;
        }
        else {

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
                writer.write(header);
                writer.newLine();

                // Remove outer array brackets
                String activities = json.trim();
                activities = activities.substring(1, activities.length() - 1);

                // Split each activity object
                String[] activityObjects = activities.split("\\},\\{");

                for (String activity : activityObjects) {

                    // Normalize JSON object
                    if (!activity.startsWith("{")) activity = "{" + activity;
                    if (!activity.endsWith("}")) activity = activity + "}";


                    String startDate = extractValue(activity, "start_date");
                    String elapsedTime = extractValue(activity, "elapsed_time");
                    String elevationGain = extractValue(activity, "total_elevation_gain");
                    String type = extractValue(activity, "type");
                    String name = extractValue(activity, "name");
                    String distanceMeters = extractValue(activity, "distance");

                    String activityDate = formatDate(startDate);
                    String activityTime = formatDuration(elapsedTime);
                    String distanceMiles = metersToMiles(distanceMeters);

//                    If the type is a run, write it to the file, else ignore it
                    if(type.equalsIgnoreCase("run")){

                        writer.write(String.join(",",
                                activityDate,
                                elevationGain,
                                activityTime,
                                type,
                                escapeCsv(name),
                                distanceMiles
                        ));
                        writer.newLine();
                    }


                }
            }
        }
    }

    // -------------------------
    // HELPERS
    // -------------------------

    private static String extractValue(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start == -1) return "";

        start += pattern.length();
        int end;

        if (json.charAt(start) == '"') {
            start++;
            end = json.indexOf("\"", start);
        } else {
            end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
        }

        return json.substring(start, end);
    }

    private static String formatDate(String isoDate) {
        Instant instant = Instant.parse(isoDate);
        return DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withLocale(Locale.US)
                .withZone(ZoneId.systemDefault())
                .format(instant);
    }

    private static String formatDuration(String secondsStr) {
        int seconds = Integer.parseInt(secondsStr);

        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, secs);
        } else {
            return String.format("%02d:%02d", minutes, secs);
        }
    }

    private static String metersToMiles(String metersStr) {
        double meters = Double.parseDouble(metersStr);
        double miles = meters / 1609.34;
        return String.format("%.2f", miles);
    }

    private static String escapeCsv(String value) {
        if (value.contains(",") || value.contains("\"")) {
            value = value.replace("\"", "\"\"");
            return "\"" + value + "\"";
        }
        return value;
    }



}