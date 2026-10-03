package com.August.chunkcleaner.command;

import com.August.chunkcleaner.ChunkCleanerPlugin;
import com.August.chunkcleaner.config.Config;
import com.August.chunkcleaner.core.ChunkProcessor;
import com.August.chunkcleaner.util.LanguageUtil;
import com.August.chunkcleaner.web.WebServerManager;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public record Command(ChunkCleanerPlugin plugin, ChunkProcessor processor,
                      Config config, WebServerManager webServerManager) implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!sender.hasPermission("chunkcleaner.admin")) {
            sender.sendMessage(LanguageUtil.msg(config,
                    "§cУ вас нет прав для выполнения этой команды.",
                    "§cYou do not have permission to execute this command."));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "process" -> handleProcess(sender, args);
            case "settings", "setting" -> handleSettings(sender, args);
            case "web" -> handleWeb(sender, args);
            case "reload" -> handleReload(sender);
            case "scan" -> processor.startDryRun(sender);
            case "start" -> processor.startProcessing(sender);
            case "stop" -> {
                processor.stopAllTasks();
                sender.sendMessage(LanguageUtil.msg(config,
                        "§cПроцесс очистки остановлен.",
                        "§cCleanup process stopped."));
            }
            case "status" -> processor.printStatus(sender);
            case "time" -> handleTime(sender, args, 1);
            case "mode" -> handleMode(sender, args, 1);
            case "delay" -> handleDelay(sender, args, 1);
            case "feedback" -> handleFeedback(sender, args, 1);
            case "lang", "language" -> handleLanguage(sender, args, 1);

            default -> sender.sendMessage(LanguageUtil.msg(config,
                    "§cНеизвестная команда. Введите §e/cclean §cдля списка команд.",
                    "§cUnknown command. Type §e/cclean §cfor help."));
        }
        return true;
    }

    private void handleProcess(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(LanguageUtil.msg(config,
                    "§eИспользование: §f/cclean process <scan|start|stop|status>",
                    "§eUsage: §f/cclean process <scan|start|stop|status>"));
            return;
        }

        switch (args[1].toLowerCase()) {
            case "scan" -> processor.startDryRun(sender);
            case "start" -> processor.startProcessing(sender);
            case "stop" -> {
                processor.stopAllTasks();
                sender.sendMessage(LanguageUtil.msg(config,
                        "§cПроцесс очистки остановлен.",
                        "§cCleanup process stopped."));
            }
            case "status" -> processor.printStatus(sender);
            default -> sender.sendMessage(LanguageUtil.msg(config,
                    "§eИспользование: §f/cclean process <scan|start|stop|status>",
                    "§eUsage: §f/cclean process <scan|start|stop|status>"));
        }
    }

    private void handleSettings(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(LanguageUtil.msg(config,
                    "§6===== Настройки ChunkCleaner =====",
                    "§6===== ChunkCleaner Settings ====="));
            sender.sendMessage(LanguageUtil.msg(config,
                    "§e/cclean settings time <тики> §7(текущий: §a%d§7, %.1f сек)",
                    "§e/cclean settings time <ticks> §7(current: §a%d§7, %.1f sec)",
                    config.getMinInhabitedTime(), config.getMinInhabitedTime() / 20.0));
            sender.sendMessage(LanguageUtil.msg(config,
                    "§e/cclean settings mode <startup|runtime> §7(текущий: §a%s§7)",
                    "§e/cclean settings mode <startup|runtime> §7(current: §a%s§7)",
                    config.getMode()));
            sender.sendMessage(LanguageUtil.msg(config,
                    "§e/cclean settings delay <мс> §7(текущая: §a%d мс§7)",
                    "§e/cclean settings delay <ms> §7(current: §a%d ms§7)",
                    config.getRegionDelayMs()));
            sender.sendMessage(LanguageUtil.msg(config,
                    "§e/cclean settings feedback <true|false|сек> §7(статус: §a%s§7, §a%d сек§7)",
                    "§e/cclean settings feedback <true|false|sec> §7(status: §a%s§7, §a%d sec§7)",
                    config.isProgressFeedbackEnabled() ? "true" : "false",
                    config.getProgressFeedbackSeconds()));
            sender.sendMessage(LanguageUtil.msg(config,
                    "§e/cclean settings language <auto|ru|en> §7(текущий: §a%s§7)",
                    "§e/cclean settings language <auto|ru|en> §7(current: §a%s§7)",
                    config.getLanguage()));
            return;
        }

        switch (args[1].toLowerCase()) {
            case "time" -> handleTime(sender, args, 2);
            case "mode" -> handleMode(sender, args, 2);
            case "delay" -> handleDelay(sender, args, 2);
            case "feedback" -> handleFeedback(sender, args, 2);
            case "lang", "language" -> handleLanguage(sender, args, 2);
            default -> sender.sendMessage(LanguageUtil.msg(config,
                    "§eИспользование: §f/cclean settings <time|mode|delay|feedback|language> [значение]",
                    "§eUsage: §f/cclean settings <time|mode|delay|feedback|language> [value]"));
        }
    }

    private void handleTime(CommandSender sender, String[] args, int valIdx) {
        if (args.length > valIdx) {
            try {
                long time = Long.parseLong(args[valIdx]);
                if (time < 0) {
                    sender.sendMessage(LanguageUtil.msg(config,
                            "§cВремя не может быть отрицательным!",
                            "§cTime cannot be negative!"));
                    return;
                }
                config.setMinInhabitedTime(time);
                if (webServerManager != null && webServerManager.isRunning()) {
                    webServerManager.broadcastStatus();
                }
                double seconds = time / 20.0;
                sender.sendMessage(LanguageUtil.msg(config,
                        "§aМинимальное время нахождения в чанке установлено: %d тиков (%.1f сек).",
                        "§aMinimum inhabited time set to: %d ticks (%.1f sec).",
                        time, seconds));
            } catch (NumberFormatException e) {
                sender.sendMessage(LanguageUtil.msg(config,
                        "§cУкажите целое число тиков!",
                        "§cPlease specify an integer number of ticks!"));
            }
        } else {
            double seconds = config.getMinInhabitedTime() / 20.0;
            sender.sendMessage(LanguageUtil.msg(config,
                    "§eИспользование: §f/cclean settings time <тики> §7(текущий: §a%d§7, %.1f сек)",
                    "§eUsage: §f/cclean settings time <ticks> §7(current: §a%d§7, %.1f sec)",
                    config.getMinInhabitedTime(), seconds));
        }
    }

    private void handleMode(CommandSender sender, String[] args, int valIdx) {
        if (args.length > valIdx && (args[valIdx].equalsIgnoreCase("STARTUP") || args[valIdx].equalsIgnoreCase("RUNTIME"))) {
            config.setMode(args[valIdx].toUpperCase());
            if (webServerManager != null && webServerManager.isRunning()) {
                webServerManager.broadcastStatus();
            }
            sender.sendMessage(LanguageUtil.msg(config,
                    "§aРежим очистки изменен на: §e%s",
                    "§aCleanup mode changed to: §e%s",
                    config.getMode()));
        } else {
            sender.sendMessage(LanguageUtil.msg(config,
                    "§eИспользование: §f/cclean settings mode <startup|runtime> §7(текущий: §a%s§7)",
                    "§eUsage: §f/cclean settings mode <startup|runtime> §7(current: §a%s§7)",
                    config.getMode()));
        }
    }

    private void handleDelay(CommandSender sender, String[] args, int valIdx) {
        if (args.length > valIdx) {
            try {
                long ms = Long.parseLong(args[valIdx]);
                if (ms < 0) {
                    sender.sendMessage(LanguageUtil.msg(config,
                            "§cЗадержка не может быть отрицательной!",
                            "§cDelay cannot be negative!"));
                    return;
                }
                config.setRegionDelayMs(ms);
                if (webServerManager != null && webServerManager.isRunning()) {
                    webServerManager.broadcastStatus();
                }
                sender.sendMessage(LanguageUtil.msg(config,
                        "§aЗадержка между регионами установлена: %d мс.",
                        "§aRegion delay set to: %d ms.",
                        ms));
            } catch (NumberFormatException e) {
                sender.sendMessage(LanguageUtil.msg(config,
                        "§cУкажите целое число миллисекунд (например: 10)!",
                        "§cPlease specify integer milliseconds (e.g. 10)!"));
            }
        } else {
            sender.sendMessage(LanguageUtil.msg(config,
                    "§eИспользование: §f/cclean settings delay <мс> §7(текущая: §a%d мс§7)",
                    "§eUsage: §f/cclean settings delay <ms> §7(current: §a%d ms§7)",
                    config.getRegionDelayMs()));
        }
    }

    private void handleFeedback(CommandSender sender, String[] args, int valIdx) {
        if (args.length > valIdx) {
            String arg1 = args[valIdx].trim().toLowerCase();

            if (arg1.equals("true") || arg1.equals("on") || arg1.equals("enable") || arg1.equals("1")) {
                config.setProgressFeedbackEnabled(true);
                if (args.length > valIdx + 1) {
                    try {
                        int sec = Integer.parseInt(args[valIdx + 1]);
                        if (sec >= 1) {
                            config.setProgressFeedbackSeconds(sec);
                        }
                    } catch (NumberFormatException ignored) {}
                }
                sender.sendMessage(LanguageUtil.msg(config,
                        "§aОтчеты о прогрессе включены (интервал: %d сек).",
                        "§aProgress feedback enabled (interval: %d sec).",
                        config.getProgressFeedbackSeconds()));
            } else if (arg1.equals("false") || arg1.equals("off") || arg1.equals("disable") || arg1.equals("0")) {
                config.setProgressFeedbackEnabled(false);
                sender.sendMessage(LanguageUtil.msg(config,
                        "§cОтчеты о прогрессе выключены.",
                        "§cProgress feedback disabled."));
            } else {
                try {
                    int sec = Integer.parseInt(arg1);
                    if (sec < 1) {
                        sender.sendMessage(LanguageUtil.msg(config,
                                "§cИнтервал должен быть не менее 1 секунды!",
                                "§cInterval must be at least 1 second!"));
                        return;
                    }
                    config.setProgressFeedbackSeconds(sec);
                    if (args.length > valIdx + 1) {
                        String arg2 = args[valIdx + 1].trim().toLowerCase();
                        if (arg2.equals("true") || arg2.equals("on") || arg2.equals("enable") || arg2.equals("1")) {
                            config.setProgressFeedbackEnabled(true);
                        } else if (arg2.equals("false") || arg2.equals("off") || arg2.equals("disable") || arg2.equals("0")) {
                            config.setProgressFeedbackEnabled(false);
                        }
                    }
                    sender.sendMessage(LanguageUtil.msg(config,
                            "§aИнтервал отчетов о прогрессе установлен: %d сек (включено: %s).",
                            "§aProgress feedback interval set to: %d sec (enabled: %s).",
                            sec, config.isProgressFeedbackEnabled()));
                } catch (NumberFormatException e) {
                    sender.sendMessage(LanguageUtil.msg(config,
                            "§cУкажите true/false или целое число секунд!",
                            "§cPlease specify true/false or integer seconds!"));
                    return;
                }
            }

            if (webServerManager != null && webServerManager.isRunning()) {
                webServerManager.broadcastStatus();
            }
        } else {
            sender.sendMessage(LanguageUtil.msg(config,
                    "§eИспользование: §f/cclean settings feedback <true|false|секунды> §7(включено: §a%s§7, интервал: §a%d сек§7)",
                    "§eUsage: §f/cclean settings feedback <true|false|seconds> §7(enabled: §a%s§7, interval: §a%d sec§7)",
                    config.isProgressFeedbackEnabled(),
                    config.getProgressFeedbackSeconds()));
        }
    }

    private void handleLanguage(CommandSender sender, String[] args, int valIdx) {
        if (args.length > valIdx) {
            String lang = args[valIdx].trim().toLowerCase();
            if (lang.equals("ru") || lang.equals("en") || lang.equals("auto")) {
                config.setLanguage(lang);
                sender.sendMessage(LanguageUtil.msg(config,
                        "§aЯзык успешно изменен на: §e" + lang,
                        "§aLanguage successfully changed to: §e" + lang));
            } else {
                sender.sendMessage(LanguageUtil.msg(config,
                        "§cДопустимые варианты языка: §eauto, ru, en",
                        "§cAllowed language options: §eauto, ru, en"));
            }
        } else {
            sender.sendMessage(LanguageUtil.msg(config,
                    "§eИспользование: §f/cclean settings language <auto|ru|en> §7(текущий: §a%s§7)",
                    "§eUsage: §f/cclean settings language <auto|ru|en> §7(current: §a%s§7)",
                    config.getLanguage()));
        }
    }

    private void handleWeb(CommandSender sender, String[] args) {
        if (webServerManager == null) {
            sender.sendMessage("§cВеб-сервер недоступен.");
            return;
        }

        if (args.length >= 2) {
            switch (args[1].toLowerCase()) {
                case "start" -> {
                    if (webServerManager.isRunning()) {
                        sender.sendMessage(LanguageUtil.msg(config,
                                "§eВеб-интерфейс уже запущен: §a" + webServerManager.getWebUrl(),
                                "§eWeb interface is already running: §a" + webServerManager.getWebUrl()));
                        return;
                    }
                    int port = config.getWebPort();
                    if (args.length >= 3) {
                        try {
                            port = Integer.parseInt(args[2]);
                        } catch (NumberFormatException ignored) {}
                    }
                    if (webServerManager.start(port)) {
                        sender.sendMessage(LanguageUtil.msg(config,
                                "§aВеб-интерфейс запущен на порту §e" + webServerManager.getBoundPort() + "§a!",
                                "§aWeb interface started on port §e" + webServerManager.getBoundPort() + "§a!"));
                        sender.sendMessage(LanguageUtil.msg(config,
                                "§6Ссылка для просмотра: §e" + webServerManager.getWebUrl(),
                                "§6Viewer URL: §e" + webServerManager.getWebUrl()));
                    } else {
                        sender.sendMessage(LanguageUtil.msg(config,
                                "§cНе удалось запустить веб-интерфейс. Проверьте консоль.",
                                "§cFailed to start web interface. Check console for details."));
                    }
                }
                case "stop" -> {
                    if (!webServerManager.isRunning()) {
                        sender.sendMessage(LanguageUtil.msg(config,
                                "§7Веб-интерфейс не был запущен.",
                                "§7Web interface is not running."));
                    } else {
                        webServerManager.stop();
                        sender.sendMessage(LanguageUtil.msg(config,
                                "§cВеб-интерфейс остановлен.",
                                "§cWeb interface stopped."));
                    }
                }
                case "status" -> {
                    if (webServerManager.isRunning()) {
                        sender.sendMessage(LanguageUtil.msg(config,
                                "§aВеб-интерфейс активен: §e" + webServerManager.getWebUrl(),
                                "§aWeb interface is active: §e" + webServerManager.getWebUrl()));
                    } else {
                        sender.sendMessage(LanguageUtil.msg(config,
                                "§7Веб-интерфейс выключен. Запустите: §e/cclean web start",
                                "§7Web interface is offline. Start it: §e/cclean web start"));
                    }
                }
                case "port" -> {
                    if (args.length >= 3) {
                        try {
                            int port = Integer.parseInt(args[2]);
                            if (port < 1 || port > 65535) {
                                sender.sendMessage(LanguageUtil.msg(config,
                                        "§cПорт должен быть в диапазоне от 1 до 65535!",
                                        "§cPort must be between 1 and 65535!"));
                                return;
                            }
                            config.setWebPort(port);
                            sender.sendMessage(LanguageUtil.msg(config,
                                    "§aПорт веб-сервера по умолчанию установлен: §e" + port,
                                    "§aDefault web server port set to: §e" + port));
                        } catch (NumberFormatException e) {
                            sender.sendMessage(LanguageUtil.msg(config,
                                    "§cУкажите целое число (порт, например: 8765)!",
                                    "§cPlease specify an integer port (e.g. 8765)!"));
                        }
                    } else {
                        sender.sendMessage(LanguageUtil.msg(config,
                                "§eИспользование: §f/cclean web port <порт> §7(текущий: §a" + config.getWebPort() + "§7)",
                                "§eUsage: §f/cclean web port <port> §7(current: §a" + config.getWebPort() + "§7)"));
                    }
                }
                case "host" -> {
                    if (args.length >= 3) {
                        String host = args[2].trim();
                        config.setWebHost(host);
                        sender.sendMessage(LanguageUtil.msg(config,
                                "§aХост привязки веб-сервера установлен: §e" + host,
                                "§aWeb server host binding set to: §e" + host));
                    } else {
                        sender.sendMessage(LanguageUtil.msg(config,
                                "§eИспользование: §f/cclean web host <хост> §7(текущий: §a" + config.getWebHost() + "§7)",
                                "§eUsage: §f/cclean web host <host> §7(current: §a" + config.getWebHost() + "§7)"));
                    }
                }
                case "timeout" -> {
                    if (args.length >= 3) {
                        try {
                            int min = Integer.parseInt(args[2]);
                            if (min < 0) {
                                sender.sendMessage(LanguageUtil.msg(config,
                                        "§cТаймаут не может быть отрицательным!",
                                        "§cTimeout cannot be negative!"));
                                return;
                            }
                            config.setWebIdleTimeoutMinutes(min);
                            sender.sendMessage(LanguageUtil.msg(config,
                                    "§aТаймаут автоотключения веб-сервера установлен: §e" + min + " мин.",
                                    "§aWeb server idle timeout set to: §e" + min + " min."));
                        } catch (NumberFormatException e) {
                            sender.sendMessage(LanguageUtil.msg(config,
                                    "§cУкажите целое число минут!",
                                    "§cPlease specify integer minutes!"));
                        }
                    } else {
                        sender.sendMessage(LanguageUtil.msg(config,
                                "§eИспользование: §f/cclean web timeout <минуты> §7(текущий: §a" + config.getWebIdleTimeoutMinutes() + "§7)",
                                "§eUsage: §f/cclean web timeout <minutes> §7(current: §a" + config.getWebIdleTimeoutMinutes() + "§7)"));
                    }
                }
                case "autostart" -> {
                    if (args.length >= 3) {
                        String val = args[2].trim().toLowerCase();
                        if (val.equals("true") || val.equals("false")) {
                            boolean autoStart = Boolean.parseBoolean(val);
                            config.setWebAutoStart(autoStart);
                            sender.sendMessage(LanguageUtil.msg(config,
                                    "§aАвтозапуск веб-сервера при сканировании: §e" + autoStart,
                                    "§aWeb server autostart on scan: §e" + autoStart));
                        } else {
                            sender.sendMessage(LanguageUtil.msg(config,
                                    "§cУкажите true или false!",
                                    "§cPlease specify true or false!"));
                        }
                    } else {
                        sender.sendMessage(LanguageUtil.msg(config,
                                "§eИспользование: §f/cclean web autostart <true|false> §7(текущий: §a" + config.isWebAutoStart() + "§7)",
                                "§eUsage: §f/cclean web autostart <true|false> §7(current: §a" + config.isWebAutoStart() + "§7)"));
                    }
                }
                default -> sender.sendMessage(LanguageUtil.msg(config,
                        "§eИспользование: §f/cclean web <start|stop|status|port|host|timeout|autostart> [значение]",
                        "§eUsage: §f/cclean web <start|stop|status|port|host|timeout|autostart> [value]"));
            }
        } else {
            if (webServerManager.isRunning()) {
                sender.sendMessage(LanguageUtil.msg(config,
                        "§aВеб-интерфейс активен: §e" + webServerManager.getWebUrl(),
                        "§aWeb interface is active: §e" + webServerManager.getWebUrl()));
            } else {
                sender.sendMessage(LanguageUtil.msg(config,
                        "§eИспользование: §f/cclean web <start|stop|status|port|host|timeout|autostart> [значение]",
                        "§eUsage: §f/cclean web <start|stop|status|port|host|timeout|autostart> [value]"));
            }
        }
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        if (webServerManager != null && webServerManager.isRunning()) {
            webServerManager.broadcastStatus();
        }
        sender.sendMessage(LanguageUtil.msg(config,
                "§aКонфигурация перезагружена!",
                "§aConfiguration reloaded!"));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(LanguageUtil.msg(config,
                "§6===== ChunkCleaner Команды =====",
                "§6===== ChunkCleaner Commands ====="));
        sender.sendMessage(LanguageUtil.msg(config,
                "§e/cclean process <scan|start|stop|status> §7- Управление процессом очистки",
                "§e/cclean process <scan|start|stop|status> §7- Cleanup process management"));
        sender.sendMessage(LanguageUtil.msg(config,
                "§e/cclean settings <time|mode|delay|feedback|language> §7- Настройки плагина",
                "§e/cclean settings <time|mode|delay|feedback|language> §7- Plugin settings"));
        sender.sendMessage(LanguageUtil.msg(config,
                "§e/cclean web <start|stop|status|port|host|timeout|autostart> §7- Управление веб-картой",
                "§e/cclean web <start|stop|status|port|host|timeout|autostart> §7- Web map server management"));
        sender.sendMessage(LanguageUtil.msg(config,
                "§e/cclean reload §7- Перезагрузка конфигурации",
                "§e/cclean reload §7- Reload configuration"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, org.bukkit.command.Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("chunkcleaner.admin")) {
            return completions;
        }

        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0],
                    Arrays.asList("process", "settings", "web", "reload"),
                    completions);
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "process" -> StringUtil.copyPartialMatches(args[1],
                        Arrays.asList("scan", "start", "stop", "status"),
                        completions);
                case "settings", "setting" -> StringUtil.copyPartialMatches(args[1],
                        Arrays.asList("time", "mode", "delay", "feedback", "language"),
                        completions);
                case "web" -> StringUtil.copyPartialMatches(args[1],
                        Arrays.asList("start", "stop", "status", "port", "host", "timeout", "autostart"),
                        completions);
                case "feedback" -> StringUtil.copyPartialMatches(args[1],
                        Arrays.asList("true", "false", "5", "10", "20", "30", "60"),
                        completions);
            }
        } else if (args.length == 3) {
            String cat = args[0].toLowerCase();
            String sub = args[1].toLowerCase();
            switch (cat) {
                case "settings", "setting" -> {
                    switch (sub) {
                        case "time" -> StringUtil.copyPartialMatches(args[2],
                                Arrays.asList("0", "20", "1200", "6000"), completions);
                        case "mode" -> StringUtil.copyPartialMatches(args[2],
                                Arrays.asList("STARTUP", "RUNTIME"), completions);
                        case "delay" -> StringUtil.copyPartialMatches(args[2],
                                Arrays.asList("0", "5", "10", "20"), completions);
                        case "feedback" -> StringUtil.copyPartialMatches(args[2],
                                Arrays.asList("true", "false", "5", "10", "20", "30", "60"), completions);
                        case "lang", "language" -> StringUtil.copyPartialMatches(args[2],
                                Arrays.asList("auto", "ru", "en"), completions);
                    }
                }
                case "web" -> {
                    switch (sub) {
                        case "start" -> StringUtil.copyPartialMatches(args[2],
                                Arrays.asList(String.valueOf(config.getWebPort()), "8080", "8123", "8765"),
                                completions);
                        case "port" -> StringUtil.copyPartialMatches(args[2],
                                Arrays.asList(String.valueOf(config.getWebPort()), "8080", "8123", "8765", "9000"),
                                completions);
                        case "host" -> StringUtil.copyPartialMatches(args[2],
                                Arrays.asList("127.0.0.1", "0.0.0.0"),
                                completions);
                        case "timeout" -> StringUtil.copyPartialMatches(args[2],
                                Arrays.asList("0", "5", "10", "15", "30", "60"),
                                completions);
                        case "autostart" -> StringUtil.copyPartialMatches(args[2],
                                Arrays.asList("true", "false"),
                                completions);
                    }
                }
                case "feedback" -> {
                    if (args[1].equalsIgnoreCase("true") || args[1].equalsIgnoreCase("false")) {
                        StringUtil.copyPartialMatches(args[2],
                                Arrays.asList("5", "10", "20", "30", "60"), completions);
                    } else {
                        StringUtil.copyPartialMatches(args[2],
                                Arrays.asList("true", "false"), completions);
                    }
                }
            }
        } else if (args.length == 4) {
            String cat = args[0].toLowerCase();
            String sub = args[1].toLowerCase();
            if (("settings".equals(cat) || "setting".equals(cat)) && "feedback".equals(sub)) {
                if (args[2].equalsIgnoreCase("true") || args[2].equalsIgnoreCase("false")) {
                    StringUtil.copyPartialMatches(args[3],
                            Arrays.asList("5", "10", "20", "30", "60"), completions);
                } else {
                    StringUtil.copyPartialMatches(args[3],
                            Arrays.asList("true", "false"), completions);
                }
            }
        }
        Collections.sort(completions);
        return completions;
    }
}