"""Offline, insert-only import. Requires Python 3.10+ and PyMySQL. Default: dry run."""
import argparse
import getpass
import gzip
import hashlib
import io
import os
import re
import uuid
import pymysql

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--module', required=True, choices=['backpacks', 'wardrobe', 'astral'])
    p.add_argument('--host', default='127.0.0.1')
    p.add_argument('--port', type=int, default=3306)
    p.add_argument('--user', required=True)
    p.add_argument('--source-db', required=True)
    p.add_argument('--target-db', required=True)
    p.add_argument('--table', help='Override legacy table name')
    p.add_argument('--group', default='main')
    p.add_argument('--legacy-group', default='main', help='Only for the old Astral Sync table')
    p.add_argument('--apply', action='store_true', help='Commit insert-only migration; all participating servers must be stopped')
    args = p.parse_args()
    table = args.table or {'backpacks': 'backpack_data', 'wardrobe': 'aw_wardrobe_sync', 'astral': 'astral_sync_players'}[args.module]
    for name in [table, args.source_db, args.target_db]:
        if not re.fullmatch(r'[A-Za-z0-9_]+', name):
            p.error('Database/table names must contain only ASCII letters, digits and underscores')
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,48}', args.group):
        p.error('Invalid sync group')
    password = os.environ['UMS_MIGRATION_PASSWORD'] if 'UMS_MIGRATION_PASSWORD' in os.environ else getpass.getpass('MySQL password: ')
    options = dict(host=args.host, port=args.port, user=args.user, password=password, connect_timeout=5,
                   read_timeout=60, write_timeout=60, charset='utf8mb4')
    source = pymysql.connect(**options, cursorclass=pymysql.cursors.SSCursor)
    target = pymysql.connect(**options, database=args.target_db, autocommit=False)
    try:
        with target.cursor() as cur:
            cur.execute('SELECT COUNT(*) FROM ums_leases WHERE grp=%s AND expires>CURRENT_TIMESTAMP(6)', (args.group,))
            if cur.fetchone()[0]:
                raise RuntimeError('Active leases exist. Stop all participating servers and wait for lease expiry.')
            cur.execute('SELECT COUNT(*) FROM ums_resources WHERE grp=%s AND expires>CURRENT_TIMESTAMP(6)', (args.group,))
            if cur.fetchone()[0]:
                raise RuntimeError('Active resource leases exist.')
        columns = {'backpacks': 'uuid,backpack_nbt', 'wardrobe': 'uuid,data', 'astral': 'player_uuid,payload'}[args.module]
        sql = f'SELECT {columns} FROM `{args.source_db}`.`{table}`'
        params = ()
        if args.module == 'astral':
            sql += ' WHERE sync_group=%s'
            params = (args.legacy_group,)
        count = inserted = 0
        with source.cursor() as rows, target.cursor() as out:
            rows.execute(sql, params)
            for key, payload in rows:
                key = str(uuid.UUID(key))
                if payload is None:
                    continue
                data = bytes(payload)
                if args.module == 'backpacks':
                    with gzip.GzipFile(fileobj=io.BytesIO(data)) as stream:
                        data = stream.read(32 * 1024 * 1024 + 1)
                if not 4 <= len(data) <= 32 * 1024 * 1024 or data[0] != 10:
                    raise ValueError(f'Invalid compound NBT payload for {key}; import aborted')
                count += 1
                if args.apply:
                    out.execute('INSERT IGNORE INTO ums_resources(grp,module,id,payload,sha,revision) VALUES(%s,%s,%s,%s,%s,1)',
                                (args.group, args.module, key, data, hashlib.sha256(data).hexdigest()))
                    inserted += out.rowcount
        if args.apply:
            target.commit()
        else:
            target.rollback()
        print(f'Validated {count} legacy rows. Inserted {inserted}. Existing destination rows are never overwritten.')
        if not args.apply:
            print('DRY RUN ONLY. Re-run with --apply after stopping all servers. Legacy history is not migrated.')
    except Exception:
        target.rollback()
        raise
    finally:
        source.close()
        target.close()

if __name__ == '__main__':
    main()
