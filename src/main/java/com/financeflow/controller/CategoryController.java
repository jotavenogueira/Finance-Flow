package com.financeflow.controller;

import com.financeflow.model.Category;
import com.financeflow.model.User;
import com.financeflow.usecase.CategoryUsecase;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CategoryController {

    private final CategoryUsecase categories;

    public CategoryController(CategoryUsecase categories) {
        this.categories = categories;
    }

    record CreateCategoryRequest(String name) {
    }

    @GetMapping("/categories")
    public List<Category> getCategories(@RequestAttribute(AuthInterceptor.AUTHENTICATED_USER) User user) {
        return categories.getCategories(user.id());
    }

    @PostMapping("/categories")
    public ResponseEntity<Category> createCategory(@RequestAttribute(AuthInterceptor.AUTHENTICATED_USER) User user,
                                                   @RequestBody CreateCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categories.createCategory(user.id(), request.name()));
    }
}
