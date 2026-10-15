package main

import (
	"log"
	"sync/atomic"
	"time"
	"unsafe"
)

// Stats — общие счётчики, которые одновременно трогают диспетчер и воркеры.
//
// Поля именно типов sync/atomic, а не голые int64 с atomic.AddInt64: на
// 32-битных ARM обычное int64-поле, лежащее после int32, попадает на границу
// 4 байт, и первая же атомарная операция над ним убивает процесс с
// «panic: unaligned 64-bit atomic operation». atomic.Int64 выравнивает себя
// сам, поэтому порядок полей здесь больше ничего не ломает.
type Stats struct {
	ActiveConnections atomic.Int32
	TotalBytesUp      atomic.Int64
	TotalBytesDown    atomic.Int64
}

// Страховка от возврата бага: если кто-то заменит atomic.Int64 обратно на
// голый int64 и поле съедет с границы 8 байт, сборка под 32-битный ARM
// сломается здесь — а не в рантайме на чужом телефоне.
const (
	_ uintptr = 0 - unsafe.Offsetof(Stats{}.TotalBytesUp)%8
	_ uintptr = 0 - unsafe.Offsetof(Stats{}.TotalBytesDown)%8
)

func NewStats() *Stats {
	return &Stats{}
}

func (s *Stats) RunLoop(shutdown <-chan struct{}) {
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-shutdown:
			return
		case <-ticker.C:
			active := s.ActiveConnections.Load()
			up := s.TotalBytesUp.Load()
			down := s.TotalBytesDown.Load()
			totalMB := float64(up+down) / (1024.0 * 1024.0)

			log.Printf("[СТАТИСТИКА] Активных: %d | Трафик: %.2f МБ", active, totalMB)
		}
	}
}
