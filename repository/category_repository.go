package repository

import (
	"database/sql"
	"errors"
	"fmt"

	"github.com/jotavenogueira/Finance-Flow/model"
)

type CategoryRepository struct {
	connection *sql.DB
}

func NewCategoryRepository(connection *sql.DB) CategoryRepository {
	return CategoryRepository{connection: connection}
}

func (cr *CategoryRepository) GetCategories(userID int) ([]model.Category, error) {
	rows, err := cr.connection.Query(
		"SELECT id, name FROM categories WHERE user_id = $1 ORDER BY lower(name), id", userID,
	)
	if err != nil {
		return nil, fmt.Errorf("list categories: %w", err)
	}
	defer rows.Close()

	categories := make([]model.Category, 0)
	for rows.Next() {
		var category model.Category
		if err := rows.Scan(&category.ID, &category.Name); err != nil {
			return nil, fmt.Errorf("read category: %w", err)
		}
		categories = append(categories, category)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate categories: %w", err)
	}
	return categories, nil
}

func (cr *CategoryRepository) CreateCategory(userID int, name string) (model.Category, error) {
	var category model.Category
	err := cr.connection.QueryRow(
		"INSERT INTO categories (user_id, name) VALUES ($1, $2) RETURNING id, name", userID, name,
	).Scan(&category.ID, &category.Name)
	var postgresError interface{ SQLState() string }
	if errors.As(err, &postgresError) && postgresError.SQLState() == "23505" {
		return model.Category{}, model.ErrConflict
	}
	if err != nil {
		return model.Category{}, fmt.Errorf("create category: %w", err)
	}
	return category, nil
}
