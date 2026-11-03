package controller

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/jotavenogueira/Finance-Flow/model"
	"github.com/jotavenogueira/Finance-Flow/usecase"
)

type CategoryController struct {
	categoryUsecase usecase.CategoryUsecase
}

func NewCategoryController(categoryUsecase usecase.CategoryUsecase) CategoryController {
	return CategoryController{categoryUsecase: categoryUsecase}
}

type createCategoryRequest struct {
	Name string `json:"name"`
}

func (c *CategoryController) GetCategories(ctx *gin.Context) {
	categories, err := c.categoryUsecase.GetCategories(CurrentUserID(ctx))
	if err != nil {
		respondError(ctx, err)
		return
	}
	ctx.JSON(http.StatusOK, categories)
}

func (c *CategoryController) CreateCategory(ctx *gin.Context) {
	var request createCategoryRequest
	if err := ctx.ShouldBindJSON(&request); err != nil {
		respondError(ctx, model.ErrInvalidInput)
		return
	}
	category, err := c.categoryUsecase.CreateCategory(CurrentUserID(ctx), request.Name)
	if err != nil {
		respondError(ctx, err)
		return
	}
	ctx.JSON(http.StatusCreated, category)
}
