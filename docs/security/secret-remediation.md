# Secret remediation

## Findings and rotation

The original repository contained a MySQL password and a Redis password in tracked YAML/Java configuration. Model API keys could also be exposed through request/response and HTTP wire logging. Rotate the historical MySQL and Redis credentials immediately, and rotate every model, MinIO, or external-provider credential that was ever used with a checked-out copy of that configuration.

Current configuration resolves secrets only from environment variables. Database model records store references such as `env:AI_CHAT_API_KEY`; resolved values are never returned by APIs or written to logs.

## Cleaning Git history

History rewriting is an owner-operated maintenance window because every clone must be re-synchronised. After backups and credential rotation:

```bash
pipx install git-filter-repo
git clone --mirror https://github.com/makabaka165/AI_dianping.git
cd AI_dianping.git
git filter-repo --replace-text ../secret-replacements.txt
git push --force --mirror
```

`secret-replacements.txt` should contain every exact leaked value in git-filter-repo replacement format. Verify all branches and tags with a secret scanner before reopening pushes. Protected branches and open pull requests must be coordinated by the repository owner.

## Development rules

- Never commit `.env`, IDE run configurations containing secrets, tokens, private keys, or production connection strings.
- Commit only `.env.example` placeholders.
- Use `env:VARIABLE_NAME` or a production Secret Manager reference in model/tool definitions.
- Keep LangChain4j body logging, HTTP wire logging, prompt bodies, retrieved document bodies, and sensitive tool arguments disabled.
- Run a secret scanner in pre-commit and CI; reject detected high-entropy tokens and known credential formats.
