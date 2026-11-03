package usecase

import (
	"fmt"
	"strings"
	"unicode/utf8"

	"github.com/jotavenogueira/Finance-Flow/model"
)

type CategoryStore interface {
	GetCategories(userID int) ([]model.Category, error)
	CreateCategory(userID int, name string) (model.Category, error)
}

type CategoryUsecase struct {
	repository CategoryStore
}

func NewCategoryUsecase(categoryRepository CategoryStore) CategoryUsecase {
	return CategoryUsecase{repository: categoryRepository}
}

func (cu *CategoryUsecase) GetCategories(userID int) ([]model.Category, error) {
	return cu.repository.GetCategories(userID)
}

func (cu *CategoryUsecase) CreateCategory(userID int, name string) (model.Category, error) {
	name = strings.TrimSpace(name)
	if length := utf8.RuneCountInString(name); length < 1 || length > 100 {
		return model.Category{}, fmt.Errorf("%w: name must contain 1 to 100 characters", model.ErrInvalidInput)
	}
	return cu.repository.CreateCategory(userID, name)
}
