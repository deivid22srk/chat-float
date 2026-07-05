package chatfloat

// Models used by the Go backend.

// Account represents a user identity stored in Supabase.
type Account struct {
        Token        string `json:"token"`
        Username     string `json:"username"`
        AvatarBase64 string `json:"avatar_base64,omitempty"`
        AvatarURL    string `json:"avatar_url,omitempty"`
}

// ChatMessage is a single chat message shown in the UI.
// MediaURL + MediaType are set for image/audio messages.
type ChatMessage struct {
        ID           int64  `json:"id"`
        Text         string `json:"text"`
        SenderName   string `json:"sender_name"`
        SenderToken  string `json:"sender_token,omitempty"`
        SenderAvatar string `json:"sender_avatar,omitempty"`
        Timestamp    int64  `json:"timestamp"`
        IsOutgoing   bool   `json:"is_outgoing"`
        MediaURL     string `json:"media_url,omitempty"`
        MediaType    string `json:"media_type,omitempty"` // "image" | "audio"
}
