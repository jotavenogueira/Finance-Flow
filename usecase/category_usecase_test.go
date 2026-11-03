package usecase

import (
	"errors"
	"strings"
	"testing"

	"github.com/jotavenogueira/Finance-Flow/model"
)

type categoryStoreStub struct {
	created bool
	userID  int
	name    string
	err     error
}

func (s *categoryStoreStub) GetCategories(userID int) ([]model.Category, error) {
	s.userID = userID
	return []model.Category{}, s.err
}

func (s *categoryStoreStub) CreateCategory(userID int, name string) (model.Category, error) {
	s.created, s.userID, s.name = true, userID, name
	return model.Category{ID: 7, Name: name}, s.err
}

func TestCreateCategoryRejectsEmptyAndOverlongNames(t *testing.T) {
	for _, name := range []string{"", " \t\n", strings.Repeat("界", 101)} {
		store := &categoryStoreStub{}
		service := NewCategoryUsecase(store)
		_, err := service.CreateCategory(42, name)
		if !errors.Is(err, model.ErrInvalidInput) || store.created {
			t.Fatalf("invalid name reached storage or wrong error: %v", err)
		}
	}
}

func TestCreateCategoryTrimsNameAndAcceptsHundredUnicodeCharacters(t *testing.T) {
	store := &categoryStoreStub{}
	service := NewCategoryUsecase(store)
	name := strings.Repeat("界", 100)
	category, err := service.CreateCategory(42, "  "+name+"\t")
	if err != nil || category.Name != name || store.name != name || store.userID != 42 {
		t.Fatalf("valid name or owner was lost: category=%+v error=%v", category, err)
	}
}

func TestCreateCategoryPreservesDuplicateError(t *testing.T) {
	store := &categoryStoreStub{err: model.ErrConflict}
	service := NewCategoryUsecase(store)
	_, err := service.CreateCategory(42, "Food")
	if !errors.Is(err, model.ErrConflict) {
		t.Fatalf("expected duplicate category error, got %v", err)
	}
}
