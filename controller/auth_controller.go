package controller

import (
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jotavenogueira/Finance-Flow/model"
	"github.com/jotavenogueira/Finance-Flow/usecase"
)

const sessionCookieName = "financeflow_session"
const authenticatedUserKey = "financeflow.authenticated_user"

type AuthController struct {
	usecase usecase.AuthUsecase
}

func NewAuthController(authUsecase usecase.AuthUsecase) AuthController {
	return AuthController{usecase: authUsecase}
}

func (a AuthController) Register(c *gin.Context) {
	c.Header("Cache-Control", "no-store")
	var input usecase.RegisterInput
	if err := c.ShouldBindJSON(&input); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"message": "Envie nome, e-mail e senha em JSON valido."})
		return
	}
	user, session, err := a.usecase.Register(c.Request.Context(), input)
	if err != nil {
		respondError(c, err)
		return
	}
	setSessionCookie(c, session)
	c.JSON(http.StatusCreated, gin.H{"user": user})
}

func (a AuthController) Login(c *gin.Context) {
	c.Header("Cache-Control", "no-store")
	var input usecase.LoginInput
	if err := c.ShouldBindJSON(&input); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"message": "Envie e-mail e senha em JSON valido."})
		return
	}
	user, session, err := a.usecase.Login(c.Request.Context(), input)
	if err != nil {
		respondError(c, err)
		return
	}
	setSessionCookie(c, session)
	c.JSON(http.StatusOK, gin.H{"user": user})
}

func (a AuthController) Logout(c *gin.Context) {
	c.Header("Cache-Control", "no-store")
	token, err := c.Cookie(sessionCookieName)
	if err != nil {
		respondError(c, model.ErrInvalidCredentials)
		return
	}
	if err := a.usecase.Logout(c.Request.Context(), token); err != nil {
		respondError(c, err)
		return
	}
	http.SetCookie(c.Writer, &http.Cookie{
		Name: sessionCookieName, Value: "", Path: "/", MaxAge: -1,
		Expires: time.Unix(1, 0), HttpOnly: true, SameSite: http.SameSiteStrictMode,
		Secure: secureSessionCookie(c),
	})
	c.Status(http.StatusNoContent)
}

func (a AuthController) Me(c *gin.Context) {
	c.Header("Cache-Control", "no-store")
	user, valid := authenticatedUser(c)
	if !valid {
		respondError(c, model.ErrInvalidCredentials)
		return
	}
	c.JSON(http.StatusOK, gin.H{"user": user})
}

func (a AuthController) RequireAuth(c *gin.Context) {
	c.Header("Cache-Control", "no-store")
	token, err := c.Cookie(sessionCookieName)
	if err != nil {
		respondError(c, model.ErrInvalidCredentials)
		c.Abort()
		return
	}
	user, err := a.usecase.Authenticate(c.Request.Context(), token)
	if err != nil {
		respondError(c, err)
		c.Abort()
		return
	}
	c.Set(authenticatedUserKey, user)
	c.Next()
}

// CurrentUserID deve ser usado somente em rotas protegidas por RequireAuth.
func CurrentUserID(c *gin.Context) int {
	user, _ := authenticatedUser(c)
	return user.ID
}

func authenticatedUser(c *gin.Context) (model.User, bool) {
	value, exists := c.Get(authenticatedUserKey)
	if !exists {
		return model.User{}, false
	}
	user, valid := value.(model.User)
	return user, valid && user.ID > 0
}

func setSessionCookie(c *gin.Context, session usecase.Session) {
	http.SetCookie(c.Writer, &http.Cookie{
		Name: sessionCookieName, Value: session.Token, Path: "/",
		Expires: session.ExpiresAt, MaxAge: 24 * 60 * 60,
		HttpOnly: true, SameSite: http.SameSiteStrictMode, Secure: secureSessionCookie(c),
	})
}

func secureSessionCookie(c *gin.Context) bool {
	return c.Request.TLS != nil || strings.EqualFold(os.Getenv("SESSION_COOKIE_SECURE"), "true")
}
