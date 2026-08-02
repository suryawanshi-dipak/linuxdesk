package com.linuxdesk.deploy;

import com.linuxdesk.ssh.CommandOutcome;
import com.linuxdesk.ssh.SshSessionManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Post-deploy health checks (SRS FR-DEP-050–055). HTTP and TCP checks run client-side (from
 * wherever LinuxDesk is running, matching the SRS §7.6 mockup's public URL example); process,
 * systemd-unit, and arbitrary-command checks run server-side over the existing SSH session,
 * since they're inherently about the remote host's local state.
 */
public final class HealthChecker {

    public enum Type {
        NONE, HTTP, TCP_PORT, PROCESS, SYSTEMD_UNIT, COMMAND
    }

    public record Result(boolean healthy, String message) {
    }

    private HealthChecker() {
    }

    public static Result runWithRetry(Type type, String target, String expectedHttpStatus,
                                       SshSessionManager sessionManager, int retries, int intervalSeconds) {
        int attempts = Math.max(1, retries);
        Result last = new Result(false, "not run");
        for (int attempt = 1; attempt <= attempts; attempt++) {
            last = runOnce(type, target, expectedHttpStatus, sessionManager);
            if (last.healthy() || attempt == attempts) {
                return last;
            }
            try {
                Thread.sleep(Math.max(0, intervalSeconds) * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    private static Result runOnce(Type type, String target, String expectedHttpStatus, SshSessionManager sessionManager) {
        try {
            return switch (type) {
                case NONE -> new Result(true, "no health check configured");
                case HTTP -> checkHttp(target, expectedHttpStatus);
                case TCP_PORT -> checkTcpPort(target);
                case PROCESS -> checkProcess(target, sessionManager);
                case SYSTEMD_UNIT -> checkSystemdUnit(target, sessionManager);
                case COMMAND -> checkCommand(target, sessionManager);
            };
        } catch (Exception e) {
            return new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static Result checkHttp(String url, String expectedStatusText) throws IOException, InterruptedException {
        int expected = parseIntOr(expectedStatusText, 200);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        boolean ok = response.statusCode() == expected;
        return new Result(ok, "HTTP " + response.statusCode() + (ok ? "" : " (expected " + expected + ")"));
    }

    private static Result checkTcpPort(String hostPort) {
        int colonIndex = hostPort.lastIndexOf(':');
        if (colonIndex <= 0) {
            return new Result(false, "expected host:port, got \"" + hostPort + "\"");
        }
        String hostPart = hostPort.substring(0, colonIndex).trim();
        String portPart = hostPort.substring(colonIndex + 1).trim();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hostPart, Integer.parseInt(portPart)), 5000);
            return new Result(true, "connected to " + hostPort);
        } catch (Exception e) {
            return new Result(false, e.getMessage());
        }
    }

    private static Result checkProcess(String processName, SshSessionManager sessionManager) throws IOException {
        CommandOutcome outcome = sessionManager.runCommand("pgrep -f " + shellQuote(processName));
        return new Result(outcome.succeeded(), outcome.succeeded() ? "process running" : "no matching process");
    }

    private static Result checkSystemdUnit(String unit, SshSessionManager sessionManager) throws IOException {
        CommandOutcome outcome = sessionManager.runCommand("systemctl is-active " + shellQuote(unit));
        String status = outcome.output().trim();
        return new Result("active".equals(status), "unit is " + (status.isEmpty() ? "unknown" : status));
    }

    private static Result checkCommand(String command, SshSessionManager sessionManager) throws IOException {
        CommandOutcome outcome = sessionManager.runCommand(command);
        return new Result(outcome.succeeded(), "exit status " + outcome.exitStatus());
    }

    private static int parseIntOr(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
