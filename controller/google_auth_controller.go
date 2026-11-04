package controller

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jotavenogueira/Finance-Flow/model"
	"github.com/jotavenogueira/Finance-Flow/usecase"
)

const googleBrowserCookie = "financeflow_google_browser"

type GoogleAuthController struct {
	auth usecase.AuthUsecase
	flow *usecase.GoogleOAuth
}

func NewGoogleAuthController(auth usecase.AuthUsecase, flow *usecase.GoogleOAuth) GoogleAuthController {
	return GoogleAuthController{auth: auth, flow: flow}
}

func (a GoogleAuthController) Providers(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"google": a.flow.Enabled()})
}

func (a GoogleAuthController) Start(c *gin.Context) { a.begin(c, 0, "") }
func (a GoogleAuthController) LinkStart(c *gin.Context) {
	token, _ := c.Cookie(sessionCookieName)
	a.begin(c, CurrentUserID(c), token)
}

func (a GoogleAuthController) begin(c *gin.Context, userID int, token string) {
	if !a.flow.Enabled() {
		c.Redirect(http.StatusSeeOther, "/?auth_error=google_failed")
		return
	}
	// Um cookie criado em outro alias não acompanha o callback canônico.
	if !strings.EqualFold(c.Request.Host, strings.TrimPrefix(strings.TrimPrefix(a.flow.CanonicalOrigin(), "https://"), "http://")) {
		c.Redirect(http.StatusSeeOther, a.flow.CanonicalOrigin()+"/")
		return
	}
	browserToken, err := usecase.NewGoogleBrowserToken()
	if err != nil {
		a.fail(c, "google_failed")
		return
	}
	location, err := a.flow.Begin(c.Request.Context(), browserToken, userID, token)
	if err != nil {
		a.fail(c, "google_failed")
		return
	}
	a.browserCookie(c, browserToken, int(usecase.GoogleChallengeDuration.Seconds()))
	c.Redirect(http.StatusSeeOther, location)
}

func (a GoogleAuthController) Callback(c *gin.Context) {
	a.browserCookie(c, "", -1)
	values := c.Request.URL.Query()
	if !a.flow.Enabled() || len(values["state"]) != 1 || len(values["code"]) > 1 || len(values["error"]) > 1 {
		a.fail(c, "google_expired")
		return
	}
	browserToken, err := c.Cookie(googleBrowserCookie)
	if err != nil {
		a.fail(c, "google_expired")
		return
	}
	challenge, err := a.flow.Consume(c.Request.Context(), values.Get("state"), browserToken)
	if err != nil {
		a.fail(c, "google_expired")
		return
	}
	if values.Get("error") != "" {
		if values.Get("error") == "access_denied" {
			a.fail(c, "google_denied")
		} else {
			a.fail(c, "google_failed")
		}
		return
	}
	identity, err := a.flow.Identity(c.Request.Context(), challenge, values.Get("code"))
	if err != nil {
		a.fail(c, "google_failed")
		return
	}
	if challenge.LinkUserID > 0 {
		active, err := a.flow.LinkSessionActive(c.Request.Context(), challenge)
		if err != nil || !active {
			a.fail(c, "google_expired")
			return
		}
		if err := a.auth.LinkGoogle(c.Request.Context(), challenge.LinkUserID, identity); err != nil {
			a.fail(c, googleErrorCode(err))
			return
		}
		c.Redirect(http.StatusSeeOther, "/?auth_success=google_linked")
		return
	}
	_, session, err := a.auth.GoogleLogin(c.Request.Context(), identity)
	if err != nil {
		a.fail(c, googleErrorCode(err))
		return
	}
	setSessionCookie(c, session)
	c.Redirect(http.StatusSeeOther, "/")
}

func googleErrorCode(err error) string {
	switch {
	case errors.Is(err, model.ErrGoogleAccountExists):
		return "google_account_exists"
	case errors.Is(err, model.ErrGoogleAccountConflict):
		return "google_account_conflict"
	case errors.Is(err, model.ErrGoogleEmailMismatch):
		return "google_email_mismatch"
	default:
		return "google_failed"
	}
}

func (a GoogleAuthController) fail(c *gin.Context, code string) {
	c.Redirect(http.StatusSeeOther, "/?auth_error="+code)
}

func (a GoogleAuthController) browserCookie(c *gin.Context, token string, maxAge int) {
	expires := time.Now().Add(usecase.GoogleChallengeDuration)
	if maxAge < 0 {
		expires = time.Unix(1, 0)
	}
	http.SetCookie(c.Writer, &http.Cookie{
		Name: googleBrowserCookie, Value: token, Path: "/auth/google",
		HttpOnly: true, Secure: secureSessionCookie(c) || a.flow.UsesHTTPS(),
		SameSite: http.SameSiteLaxMode, MaxAge: maxAge, Expires: expires,
	})
}
