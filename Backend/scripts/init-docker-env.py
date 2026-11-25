#!/usr/bin/env python3
"""Generate private local Docker settings; never overwrite existing settings."""
import secrets
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def read_env(path):
    if not path.exists():
        return {}
    return dict(line.split('=', 1) for line in path.read_text().splitlines()
                if '=' in line and not line.lstrip().startswith('#'))

def main():
    target = ROOT / '.env'
    if target.exists():
        print('.env already exists; left unchanged.')
        return
    values = read_env(ROOT / '.env.example')
    keycloak = read_env(ROOT / 'keycloak/.env')
    identity = read_env(ROOT / 'identity-service/.env.docker')
    for key in values:
        if key in keycloak:
            values[key] = keycloak[key]
        elif key.startswith('IDENTITY_BOOTSTRAP_') and key in identity:
            values[key] = identity[key]
        elif key.endswith('_PASSWORD') or key.endswith('_SECRET'):
            values[key] = secrets.token_hex(24)
    for name in ['identity', 'proposal', 'project']:
        old = read_env(ROOT / (name + '-service') / '.env.docker')
        for suffix in ['USERNAME', 'PASSWORD']:
            if old.get('DATABASE_' + suffix):
                values[name.upper() + '_DATABASE_' + suffix] = old['DATABASE_' + suffix]
    with target.open('x') as stream:
        stream.write(''.join(key + '=' + value + '\n' for key, value in values.items()))
    target.chmod(0o600)
    print('Created private .env. Existing service env files were left unchanged.')

if __name__ == '__main__':
    main()
