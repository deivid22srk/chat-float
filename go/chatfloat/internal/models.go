package chatfloat

// Models used by the Go backend.

// Account represents a user identity stored in Supabase.
// AvatarBase64 is kept for backwards compat but the new field is AvatarURL
// (a public URL pointing to Supabase Storage).
type Account struct {
        Token        string `json:"token"`
        Username     string `json:"username"`
        AvatarBase64 string `json:"avatar_base64,omitempty"`
        AvatarURL    string `json:"avatar_url,omitempty"`
}

// ChatMessage is a single chat message shown in the UI.
// SenderAvatar is now a URL (loaded asynchronously by the UI).
type ChatMessage struct {
        ID           int64  `json:"id"`
        Text         string `json:"text"`
        SenderName   string `json:"sender_name"`
        SenderToken  string `json:"sender_token,omitempty"`
        SenderAvatar string `json:"sender_avatar,omitempty"`
        Timestamp    int64  `json:"timestamp"`
        IsOutgoing   bool   `json:"is_outgoing"`
}
