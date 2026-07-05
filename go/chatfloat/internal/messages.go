package chatfloat

// MessageRepo is an in-memory cache of chat messages, persisted to
// <dataDir>/messages.json on every write. It is safe for concurrent use.

import (
	"os"
	"path/filepath"
	"sync"
)

type MessageRepo struct {
	mu      sync.Mutex
	msgs    []ChatMessage
	dataDir string
}

func NewMessageRepo(dataDir string) *MessageRepo {
	r := &MessageRepo{dataDir: dataDir}
	r.load()
	return r
}

func (r *MessageRepo) load() {
	if r.dataDir == "" {
		return
	}
	path := filepath.Join(r.dataDir, "messages.json")
	var msgs []ChatMessage
	if err := readJSON(path, &msgs); err == nil {
		r.msgs = msgs
	}
}

func (r *MessageRepo) persist() {
	if r.dataDir == "" {
		return
	}
	_ = os.MkdirAll(r.dataDir, 0o755)
	path := filepath.Join(r.dataDir, "messages.json")
	_ = writeJSON(path, r.msgs)
}

// Add appends a message. Duplicates (by ID) are ignored.
func (r *MessageRepo) Add(msg ChatMessage) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, existing := range r.msgs {
		if existing.ID == msg.ID {
			return
		}
	}
	r.msgs = append(r.msgs, msg)
	r.persist()
}

// AddAll appends multiple messages, skipping duplicates.
func (r *MessageRepo) AddAll(msgs []ChatMessage) {
	r.mu.Lock()
	defer r.mu.Unlock()
	existing := make(map[int64]bool, len(r.msgs))
	for _, m := range r.msgs {
		existing[m.ID] = true
	}
	for _, m := range msgs {
		if !existing[m.ID] {
			r.msgs = append(r.msgs, m)
			existing[m.ID] = true
		}
	}
	r.persist()
}

// All returns a copy of all messages, oldest first.
func (r *MessageRepo) All() []ChatMessage {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]ChatMessage, len(r.msgs))
	copy(out, r.msgs)
	return out
}

// Clear empties the cache and removes the persisted file.
func (r *MessageRepo) Clear() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.msgs = nil
	if r.dataDir != "" {
		_ = os.Remove(filepath.Join(r.dataDir, "messages.json"))
	}
}
