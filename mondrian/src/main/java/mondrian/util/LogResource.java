// Copyright (C) 2022 Sergei Semenkov
// All Rights Reserved.

package mondrian.util;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

import java.nio.charset.StandardCharsets;
import java.util.*;

import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.appender.RollingRandomAccessFileAppender;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URLEncoder;


public class LogResource extends HttpServlet {

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setCharacterEncoding("UTF-8");
        String catalinaHome = System.getProperty("catalina.home");
        if (catalinaHome == null) {
            response.setContentType("text/plain; charset=UTF-8");
            try (PrintWriter out = response.getWriter()) {
                out.println("System property 'catalina.home' is not set.");
            }
            return;
        }

        File logsDir = new File(catalinaHome, "logs");
        if (!logsDir.exists() || !logsDir.isDirectory()) {
            response.setContentType("text/plain; charset=UTF-8");
            try (PrintWriter out = response.getWriter()) {
                out.println("Logs directory does not exist: " + logsDir.getAbsolutePath());
            }
            return;
        }

        // 1) Check if pathInfo points to an appender name and serve its log file
        String pathInfo = request.getPathInfo();  // e.g. "/appenderName"
        if (pathInfo != null && !pathInfo.equals("/")) {
            String[] parts = pathInfo.split("/");
            String appenderRef = parts[parts.length - 1];

            if (!appenderRef.isEmpty()) {
                LoggerContext ctx = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
                Object appender = ctx.getConfiguration().getAppender(appenderRef);
                String result;

                if (appender instanceof FileAppender) {
                    String logFileName = ((FileAppender) appender).getFileName();
                    result = readFileContent(logFileName);
                } else if (appender instanceof RollingRandomAccessFileAppender) {
                    String logFileName = ((RollingRandomAccessFileAppender) appender).getFileName();
                    result = readFileContent(logFileName);
                } else {
                    result = "There is no such log.";
                }

                response.setContentType("text/plain; charset=UTF-8");
                try (PrintWriter out = response.getWriter()) {
                    out.print(result);
                }
                return;
            }
        }

        // 2) Otherwise, check if there's a "file" parameter to read specific file
        String fileParam = request.getParameter("file");
        if (fileParam != null && !fileParam.isEmpty()) {
            // Basic security check
            if (fileParam.contains("..") || fileParam.contains("/") || fileParam.contains("\\")) {
                response.setContentType("text/plain; charset=UTF-8");
                try (PrintWriter out = response.getWriter()) {
                    out.println("Invalid file parameter.");
                }
                return;
            }

            File requestedFile = new File(logsDir, fileParam);

            if (!requestedFile.exists() || !requestedFile.isFile()) {
                response.setContentType("text/plain; charset=UTF-8");
                try (PrintWriter out = response.getWriter()) {
                    out.println("File not found: " + fileParam);
                }
                return;
            }

            response.setContentType("text/plain; charset=UTF-8");
            try (PrintWriter out = response.getWriter()) {
                List<String> lines = java.nio.file.Files.readAllLines(requestedFile.toPath(), StandardCharsets.UTF_8);
                for (String line : lines) {
                    out.println(line);
                }
            }
            return;
        }

        // 3) No file param and no pathInfo => list all files as HTML links
        response.setContentType("text/html; charset=UTF-8");

        File[] files = logsDir.listFiles();
        if (files == null || files.length == 0) {
            try (PrintWriter out = response.getWriter()) {
                out.println("No files found inside logs directory.");
            }
            return;
        }

        List<String> fileNames = new ArrayList<>();
        for (File file : files) {
            if (file.isFile()) {
                fileNames.add(file.getName());
            }
        }
        Collections.sort(fileNames);

        try (PrintWriter out = response.getWriter()) {
            StringBuilder html = new StringBuilder();
            html.append("<html><body>\n");
            for (String fileName : fileNames) {
                String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8.toString());
                html.append("<a href=\"logs?file=").append(encodedFileName).append("\">")
                        .append(fileName).append("</a><br/>\n");
            }
            html.append("</body></html>");
            out.println(html.toString());
        }
    }

    // Helper method to read file content or return error message
    private String readFileContent(String filePath) {
        try {
            byte[] encoded = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath));
            return new String(encoded, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error reading log file: " + e.getMessage();
        }
    }

}
