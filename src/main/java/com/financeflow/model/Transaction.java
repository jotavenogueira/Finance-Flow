package com.financeflow.model;

import java.math.BigDecimal;

public record Transaction(int id, String description, BigDecimal amount, String type,
                          int categoryId, String category, String date) {
}
