#!/usr/bin/env python3
"""
LxChat 兑换码生成工具

用法:
  python generate_redemption_code.py                    # 默认: Premium, 30天
  python generate_redemption_code.py --tier Pro         # Pro, 30天
  python generate_redemption_code.py --days 90          # Premium, 90天
  python generate_redemption_code.py --tier Pro --days 365  # Pro, 1年
  python generate_redemption_code.py --count 10         # 批量生成10个

兑换码格式:
  BASE64(payloadJson) + "." + BASE64(HMAC-SHA256(BASE64(payloadJson), secretKey))

payload 包含:
  - tier: "Premium" 或 "Pro"
  - durationDays: 有效天数
  - issuedAt: 签发时间戳(毫秒)
  - expiresAt: 过期时间戳(毫秒) = issuedAt + durationDays * 86400000
  - nonce: 随机UUID(每次不同,一次性使用)
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import uuid

# HMAC 密钥 (与 redemption_native.cpp 中 XOR 混淆后的密钥一致)
SECRET_KEY = b"LxchatRedemptionHmac2026SecretKey"

B64 = base64.b64encode
B64_DECODE = base64.b64decode


def generate_code(tier: str, duration_days: int) -> str:
    """生成一个兑换码"""
    issued_at = int(time.time() * 1000)
    expires_at = issued_at + duration_days * 86400000
    nonce = str(uuid.uuid4())

    payload = {
        "tier": tier,
        "durationDays": duration_days,
        "issuedAt": issued_at,
        "expiresAt": expires_at,
        "nonce": nonce,
    }

    payload_json = json.dumps(payload, separators=(',', ':'))
    base64_payload = B64(payload_json.encode('utf-8')).decode('ascii')

    signature = hmac.new(SECRET_KEY, base64_payload.encode('ascii'), hashlib.sha256).digest()
    base64_signature = B64(signature).decode('ascii')

    return f"{base64_payload}.{base64_signature}"


def main():
    parser = argparse.ArgumentParser(description='LxChat 兑换码生成工具')
    parser.add_argument('--tier', default='Premium', choices=['Premium', 'Pro'],
                        help='会员等级 (默认: Premium)')
    parser.add_argument('--days', type=int, default=30,
                        help='有效天数 (默认: 30)')
    parser.add_argument('--count', type=int, default=1,
                        help='生成数量 (默认: 1)')
    args = parser.parse_args()

    print(f"=== LxChat 兑换码生成 ===")
    print(f"等级: {args.tier}")
    print(f"有效期: {args.days} 天")
    print(f"数量: {args.count}")
    print(f"=" * 50)

    for i in range(args.count):
        code = generate_code(args.tier, args.days)
        print(f"\n[{i+1}] {code}")

    print(f"\n{'=' * 50}")
    print(f"共生成 {args.count} 个兑换码")
    print(f"每个兑换码一次性使用，有效期 {args.days} 天")


if __name__ == '__main__':
    main()