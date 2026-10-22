# MinePanel

A modern self-hosted Minecraft server management panel powered by Docker.

MinePanel allows you to create, manage and monitor Minecraft servers through a clean web interface without manually handling Docker containers or constantly using SSH.

---

## Features

### Server Management
- Create and manage multiple Minecraft servers
- Start, stop and restart servers instantly
- Supports:
  - Vanilla
  - Paper
  - Spigot
  - Purpur
  - Folia
  - and more

### Live Monitoring
- Real-time console logs
- CPU and RAM monitoring

### Backups
- Automatic backups
- Easy restore support

### Web Interface
- Modern responsive UI
- Mobile friendly
- Multi-language support

---

# Quick Start

## Requirements

- Minecraft Server and a free Port
---

## Installation

- Download the jar from the [Modrinth Page](https://modrinth.com/plugin/minepanel)
- Start the server and wait for the panel to initialize

Open the panel in your browser:

```txt
http://ip:3000
```

---

# Compile from Source

```bash
git clone https://git.winniepat.de/winnie/MinePanel.git
cd MinePanel
gradlew build
```
- And copy the generated `build/libs/MinePanel.jar` to your server
- 
---

# Reverse Proxy / SSL

MinePanel can be secured with:
- Nginx Proxy Manager (easiest option)
- A Let's Encrypt certificate
- Your own SSL certificate


# Security Notice

MinePanel is still under active development.

Do not expose the panel publicly without:
- HTTPS
- strong passwords
- firewall protection
- proper reverse proxy configuration

---

# Roadmap

- [ ] Addon marketplace
- [ ] Scheduled tasks
- [ ] Multi-user permissions
- [ ] Metrics dashboard
- [ ] Public API

---

# Contributing

Pull requests, bug reports and feature suggestions are welcome.

1. Fork the repository
2. Create a new branch
3. Commit your changes
4. Open a pull request

---

# License

This project is licensed under the MIT License.