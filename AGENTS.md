# Hinweise für KI-Agenten

## Synchronisation zwischen Rechnern (Git)

An diesem Repository wird auf mehreren Rechnern gearbeitet. Damit die Stände nicht auseinanderlaufen, gilt für jeden KI-Agenten, egal von welchem Hersteller oder Modell:

- **Vor Arbeitsbeginn** den aktuellen Stand holen: `git pull --ff-only`. Liegen lokale, uncommittete Änderungen vor, die nicht von dir stammen, nichts überschreiben, sondern den Nutzer fragen.
- **Nach jeder abgeschlossenen Änderung** committen und pushen (`git add -A`, `git commit`, `git push`), direkt auf dem aktuellen Branch. Commit-Nachrichten auf Deutsch und aussagekräftig.
- Lehnt GitHub den Push ab, weil dort ein neuerer Stand liegt: `git pull --rebase`, danach erneut pushen. Bei Konflikten nichts erzwingen (kein `--force`, kein `reset --hard`), sondern den Nutzer informieren.
- Vor jedem Commit prüfen, dass keine echten Personendaten, Schlüssel, Tokens oder Passwörter und nichts aus per `.gitignore` ausgeschlossenen Ordnern enthalten ist.
- Ist `github.com` nicht erreichbar (z. B. im Schulnetz), den Proxy aus den Windows-Interneteinstellungen als `HTTPS_PROXY` setzen.
- In Claude Code erledigt das ein Hook (`~/.claude/hooks/git-sync.ps1`) automatisch; für alle anderen Werkzeuge gilt diese Regel trotzdem.
