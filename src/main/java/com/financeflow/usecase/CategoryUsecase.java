package com.financeflow.usecase;

import com.financeflow.model.Category;
import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CategoryUsecase {

    private final CategoryStore repository;

    public CategoryUsecase(CategoryStore repository) {
        this.repository = repository;
    }

    public List<Category> getCategories(int userId) {
        return repository.getCategories(userId);
    }

    public Category createCategory(int userId, String name) {
        String trimmed = Text.trim(name);
        int length = Text.characters(trimmed);
        if (length < 1 || length > 100) {
            throw new DomainException(DomainError.INVALID_INPUT, "name must contain 1 to 100 characters");
        }
        return repository.createCategory(userId, trimmed);
    }
}
