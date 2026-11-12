package com.lunarboreal.economy;

import org.bukkit.configuration.file.YamlConfiguration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** config.yml 的不可变快照，重载时整体替换。 */
public final class EconomySettings {

    public final String singular;
    public final String plural;
    public final int decimals;
    public final String format;
    public final boolean grouping;
    public final BigDecimal initialBalance;
    /** null 表示不限制。 */
    public final BigDecimal maxBalance;
    public final int flushInterval;
    public final int topRefresh;
    public final BigDecimal payMin;
    public final BigDecimal feePercent;
    public final BigDecimal minFee;
    /** null 表示不需要确认。 */
    public final BigDecimal confirmThreshold;
    public final int confirmTimeout;
    public final int baltopPageSize;
    public final int logPageSize;
    public final boolean vaultEnabled;
    public final String vaultName;

    public EconomySettings(YamlConfiguration c) {
        singular = c.getString("currency.singular", "金币");
        plural = c.getString("currency.plural", singular);
        decimals = Math.max(0, Math.min(8, c.getInt("currency.decimals", 2)));
        format = c.getString("currency.format", "{amount} {currency}");
        grouping = c.getBoolean("currency.grouping", true);
        initialBalance = scale(dec(c.getString("account.initial-balance", "0")));
        BigDecimal max = dec(c.getString("account.max-balance", "0"));
        maxBalance = max.signum() <= 0 ? null : scale(max);
        flushInterval = Math.max(5, c.getInt("storage.flush-interval", 30));
        topRefresh = Math.max(10, c.getInt("storage.top-refresh", 60));
        payMin = scale(dec(c.getString("pay.min-amount", "0")));
        feePercent = dec(c.getString("pay.fee-percent", "0")).max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
        minFee = scale(dec(c.getString("pay.min-fee", "0")).max(BigDecimal.ZERO));
        BigDecimal ct = dec(c.getString("pay.confirm-threshold", "0"));
        confirmThreshold = ct.signum() <= 0 ? null : ct;
        confirmTimeout = Math.max(5, c.getInt("pay.confirm-timeout", 30));
        baltopPageSize = Math.max(1, Math.min(50, c.getInt("baltop.page-size", 10)));
        logPageSize = Math.max(1, Math.min(50, c.getInt("log.page-size", 10)));
        vaultEnabled = c.getBoolean("vault.enabled", true);
        vaultName = c.getString("vault.name", "LunarCore-Economy");
    }

    public static BigDecimal dec(String s) {
        try {
            return new BigDecimal(s == null ? "0" : s.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    public BigDecimal scale(BigDecimal v) {
        return v.setScale(decimals, RoundingMode.DOWN);
    }

    /** 向上取整（用于扣款与手续费）。 */
    public BigDecimal scaleUp(BigDecimal v) {
        return v.setScale(decimals, RoundingMode.UP);
    }

    public BigDecimal fee(BigDecimal amount) {
        if (feePercent.signum() <= 0) return BigDecimal.ZERO.setScale(decimals);
        BigDecimal fee = amount.multiply(feePercent).divide(BigDecimal.valueOf(100), decimals, RoundingMode.UP);
        return fee.max(minFee);
    }

    /** 仅格式化数字部分（含千分位）。 */
    public String number(BigDecimal amount) {
        StringBuilder pattern = new StringBuilder(grouping ? "#,##0" : "0");
        if (decimals > 0) pattern.append('.').append("0".repeat(decimals));
        DecimalFormat df = new DecimalFormat(pattern.toString(), DecimalFormatSymbols.getInstance(Locale.ROOT));
        df.setRoundingMode(RoundingMode.DOWN);
        return df.format(amount);
    }

    public String format(BigDecimal amount) {
        String cur = amount.compareTo(BigDecimal.ONE) == 0 ? singular : plural;
        return format.replace("{amount}", number(amount)).replace("{currency}", cur);
    }

    /** 解析玩家输入的金额，支持 1k / 2.5m / 1w（万）后缀；非法返回 null。 */
    public BigDecimal parseAmount(String input) {
        if (input == null || input.isBlank()) return null;
        String s = input.trim().toLowerCase(Locale.ROOT).replace(",", "");
        BigDecimal mul = BigDecimal.ONE;
        if (s.endsWith("k")) { mul = BigDecimal.valueOf(1_000); s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("w") || s.endsWith("万")) { mul = BigDecimal.valueOf(10_000); s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mul = BigDecimal.valueOf(1_000_000); s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("b")) { mul = BigDecimal.valueOf(1_000_000_000); s = s.substring(0, s.length() - 1); }
        try {
            BigDecimal v = new BigDecimal(s).multiply(mul);
            if (v.abs().compareTo(new BigDecimal("1e30")) > 0) return null;
            return scale(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
