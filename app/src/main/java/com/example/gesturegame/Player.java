package com.example.gesturegame;

// Player data class — used for future expansion
// (multiplayer, save state, level system etc.)
public class Player {

    public String name;
    public int    health;
    public int    score;
    public int    level;
    public float  x;
    public float  y;

    // Gesture action states
    public boolean isAttacking;
    public boolean isBlocking;
    public boolean isJumping;

    public Player(String name) {
        this.name       = name;
        this.health     = 3;
        this.score      = 0;
        this.level      = 1;
        this.isAttacking = false;
        this.isBlocking  = false;
        this.isJumping   = false;
    }

    public void takeDamage() {
        if (!isBlocking && health > 0) {
            health--;
        }
    }

    public void addScore(int points) {
        score += points;
    }

    public boolean isAlive() {
        return health > 0;
    }

    public void reset() {
        health      = 3;
        score       = 0;
        isAttacking = false;
        isBlocking  = false;
        isJumping   = false;
    }
}