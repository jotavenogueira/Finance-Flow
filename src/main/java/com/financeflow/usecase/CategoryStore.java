package com.financeflow.usecase;

import com.financeflow.model.Category;
import java.util.List;

public interface CategoryStore {

    List<Category> getCategories(int userId);

    Category createCategory(int userId, String name);
}
