package com.financeflow.repository;

import com.financeflow.model.Category;
import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.usecase.CategoryStore;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CategoryRepository implements CategoryStore {

    private static final RowMapper<Category> CATEGORY =
            (row, index) -> new Category(row.getInt("id"), row.getString("name"));

    private final JdbcClient jdbc;

    public CategoryRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public List<Category> getCategories(int userId) {
        return jdbc.sql("SELECT id, name FROM categories WHERE user_id = ? ORDER BY lower(name), id")
                .param(userId)
                .query(CATEGORY)
                .list();
    }

    @Override
    public Category createCategory(int userId, String name) {
        try {
            return jdbc.sql("INSERT INTO categories (user_id, name) VALUES (?, ?) RETURNING id, name")
                    .params(userId, name)
                    .query(CATEGORY)
                    .single();
        } catch (DuplicateKeyException exception) {
            throw new DomainException(DomainError.CONFLICT);
        }
    }
}
