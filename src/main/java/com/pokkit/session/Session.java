package com.pokkit.session;

import java.time.Instant;

/**
 * 会话记录 — 对应 sessions 表的一行。
 */
public record Session(String id, String title, Instant createdAt, Instant updatedAt) {
}
