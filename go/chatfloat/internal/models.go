package chatfloat

// Models used by the Go backend.

// Account represents a user identity stored in Supabase.
type Account struct {
	Token        string `json:"token"`
	Username     string `json:"username"`
	AvatarBase64 string `json:"avatar_base64,omitempty"`
}

// ChatMessage is a single chat message shown in the UI.
type ChatMessage struct {
	ID           int64  `json:"id"`
	Text         string `json:"text"`
	SenderName   string `json:"sender_name"`
	SenderToken  string `json:"sender_token,omitempty"`
	SenderAvatar string `json:"sender_avatar,omitempty"`
	Timestamp    int64  `json:"timestamp"`
	IsOutgoing   bool   `json:"is_outgoing"`
}
