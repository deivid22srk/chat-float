package chatfloat

// Poller runs a background goroutine that long-polls Telegram for new
// messages and appends them to the MessageRepo. It also applies account
// envelopes (REGISTER / AVATAR) to a local known-accounts cache.

import (
	"sync"
	"time"
)

type Poller struct {
	tc       *TelegramClient
	repo     *MessageRepo
	myToken  string
	mu       sync.Mutex
	stopped  bool
	stopCh   chan struct{}
	accounts map[string]*Account // token -> account (for avatar resolution)
}

func NewPoller(tc *TelegramClient, repo *MessageRepo, myToken string) *Poller {
	return &Poller{
		tc:       tc,
		repo:     repo,
		myToken:  myToken,
		stopCh:   make(chan struct{}),
		accounts: make(map[string]*Account),
	}
}

func (p *Poller) Run() {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-p.stopCh:
			return
		case <-ticker.C:
			p.tick()
		}
	}
}

func (p *Poller) tick() {
	batch, err := p.tc.GetUpdates()
	if err != nil {
		return
	}

	// 1. Apply envelopes
	for _, env := range batch.Envelopes {
		p.applyEnvelope(env)
	}

	// 2. Persist incoming messages, enriching with avatar from cache
	if len(batch.Messages) > 0 {
		enriched := make([]ChatMessage, 0, len(batch.Messages))
		for _, m := range batch.Messages {
			if m.SenderToken != "" {
				p.mu.Lock()
				acc := p.accounts[m.SenderToken]
				p.mu.Unlock()
				if acc != nil {
					if m.SenderName == "" {
						m.SenderName = acc.Username
					}
					m.SenderAvatar = acc.AvatarBase64
				}
			}
			enriched = append(enriched, m)
		}
		p.repo.AddAll(enriched)
	}
}

func (p *Poller) applyEnvelope(env AccountEnvelope) {
	p.mu.Lock()
	defer p.mu.Unlock()
	switch env.Type {
	case "REGISTER":
		existing := p.accounts[env.Token]
		username := env.Username
		if username == "" && existing != nil {
			username = existing.Username
		}
		if username == "" {
			username = "user-" + env.Token[:4]
		}
		avatar := env.AvatarBase64
		if avatar == "" && existing != nil {
			avatar = existing.AvatarBase64
		}
		p.accounts[env.Token] = &Account{
			Token:        env.Token,
			Username:     username,
			AvatarBase64: avatar,
		}
	case "AVATAR":
		if existing, ok := p.accounts[env.Token]; ok {
			existing.AvatarBase64 = env.AvatarBase64
		} else {
			// We don't know the username yet — store with placeholder
			p.accounts[env.Token] = &Account{
				Token:        env.Token,
				Username:     "user-" + env.Token[:4],
				AvatarBase64: env.AvatarBase64,
			}
		}
	}
}

func (p *Poller) Stop() {
	p.mu.Lock()
	defer p.mu.Unlock()
	if !p.stopped {
		p.stopped = true
		close(p.stopCh)
	}
}
