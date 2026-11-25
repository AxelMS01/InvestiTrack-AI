#!/usr/bin/env python3
"""Verify a consistent audit DB snapshot using psql and normal PG* connection variables."""
import argparse
import hashlib
import json
import subprocess
import sys
from datetime import datetime, timezone
from decimal import Decimal


def canonical(value):
    if value is None:return 'n'
    if isinstance(value,bool):return 't' if value else 'f'
    if isinstance(value,dict):return 'm'+str(len(value))+':'+''.join(canonical(k)+canonical(value[k]) for k in sorted(value))
    if isinstance(value,list):return 'l'+str(len(value))+':'+''.join(canonical(v) for v in value)
    if isinstance(value,(int,Decimal)):
        s=format(Decimal(value),'f')
        if '.' in s:s=s.rstrip('0').rstrip('.')
        return 'd'+('0' if Decimal(value)==0 else s)+';'
    s=str(value);return 's'+str(len(s.encode('utf-8')))+':'+s


def instant(value):
    date=datetime.fromisoformat(value.replace('Z','+00:00')).astimezone(timezone.utc)
    fraction=('' if date.microsecond==0 else '.'+f'{date.microsecond:06d}'[:3] if date.microsecond%1000==0 else '.'+f'{date.microsecond:06d}')
    return date.strftime('%Y-%m-%dT%H:%M:%S')+fraction+'Z'


def verify(rows,expected_head=None):
    previous=None;sequence=0;count=0
    for row in rows:
        row=dict(row);expected=row.pop('eventHash')
        if row['sequenceNumber']<=sequence or row['previousHash']!=previous:raise ValueError('Broken chain order or predecessor at sequence '+str(row['sequenceNumber']))
        for key in ['occurredAt','receivedAt']:row[key]=instant(row[key])
        actual=hashlib.sha256(canonical(row).encode('utf-8')).hexdigest()
        if actual!=expected:raise ValueError('Invalid event hash at sequence '+str(row['sequenceNumber']))
        previous=expected;sequence=row['sequenceNumber'];count+=1
    if expected_head is not None and previous!=expected_head:raise ValueError('The head differs from the trusted checkpoint')
    return count,previous


def main():
    args=argparse.ArgumentParser(description=__doc__);args.add_argument('--expected-head',help='Trusted head of an immutable backup/snapshot; detects tail removal as well as modification');options=args.parse_args()
    columns=['id','sequenceNumber','occurredAt','receivedAt','actorType','actorId','action','resourceType','resourceId','projectId','outcome','correlationId','sourceService','metadata','previousHash','payloadHash','eventHash']
    def snake(s):
        import re
        return re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',s).lower()
    sql='select row_to_json(e) from (select '+','.join(snake(c)+' as "'+c+'"' for c in columns)+' from audit_event order by sequence_number) e'
    with subprocess.Popen(['psql','-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-c',sql],stdout=subprocess.PIPE,text=True,encoding='utf-8') as process:
        try:
            result=verify((json.loads(line,parse_float=Decimal) for line in process.stdout if line.strip()),options.expected_head)
            if process.wait()!=0:raise ValueError('psql could not read the audit snapshot')
        except Exception:
            process.terminate();process.wait();raise
    print('Verified',result[0],'records; head='+str(result[1]))

if __name__=='__main__':
    try:main()
    except (ValueError,OSError) as error:
        print(str(error),file=sys.stderr);sys.exit(1)
