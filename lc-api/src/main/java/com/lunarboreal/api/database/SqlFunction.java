package com.lunarboreal.api.database;

import java.sql.SQLException;

@FunctionalInterface
public interface SqlFunction<I, T> {
    T apply(I input) throws SQLException;
}
