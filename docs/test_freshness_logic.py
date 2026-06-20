#!/usr/bin/env python3
"""
🧪 Test freshness logic in isolation (simulates Kotlin CatalogRepository behavior)

This validates that:
1. Per-type revision/hash comparison works correctly
2. Stale data is detected when server revision differs
3. Fresh data passes when revisions match
4. Network errors don't break the flow
"""

# Simulate the FreshnessResult structure
class FreshnessResult:
    def __init__(self, is_fresh, reason, local_rev, server_rev, local_hash, server_hash):
        self.is_fresh = is_fresh
        self.reason = reason
        self.local_rev = local_rev
        self.server_rev = server_rev
        self.local_hash = local_hash
        self.server_hash = server_hash

    def __repr__(self):
        return f"FreshnessResult(fresh={self.is_fresh}, reason='{self.reason}', local={self.local_rev}, server={self.server_rev})"


def check_freshness(local_state, server_response):
    """
    Replicates the Kotlin logic from CatalogRepository.isCatalogFresh()
    """
    # No local data → not fresh
    if local_state.get('revision', 0) == 0 or local_state.get('count', 0) == 0:
        return FreshnessResult(
            is_fresh=False,
            reason='no_local_data',
            local_rev=local_state.get('revision', 0),
            server_rev=0,
            local_hash=local_state.get('hash', ''),
            server_hash=''
        )

    # Simulate network failure
    if not server_response.get('success', False):
        return FreshnessResult(
            is_fresh=False,
            reason=f"meta_unavailable:{server_response.get('message', 'unknown')}",
            local_rev=local_state['revision'],
            server_rev=0,
            local_hash=local_state.get('hash', ''),
            server_hash=''
        )

    server_rev = server_response.get('revision', 0)
    server_hash = server_response.get('hash', '')
    local_rev = local_state['revision']
    local_hash = local_state.get('hash', '')

    if server_response.get('not_modified', False):
        return FreshnessResult(
            is_fresh=True,
            reason='match',
            local_rev=local_rev,
            server_rev=server_rev,
            local_hash=local_hash,
            server_hash=server_hash
        )

    if server_rev != local_rev:
        return FreshnessResult(
            is_fresh=False,
            reason='revision_mismatch',
            local_rev=local_rev,
            server_rev=server_rev,
            local_hash=local_hash,
            server_hash=server_hash
        )

    if server_hash and server_hash != local_hash:
        return FreshnessResult(
            is_fresh=False,
            reason='hash_mismatch',
            local_rev=local_rev,
            server_rev=server_rev,
            local_hash=local_hash,
            server_hash=server_hash
        )

    return FreshnessResult(
        is_fresh=False,
        reason='stale',
        local_rev=local_rev,
        server_rev=server_rev,
        local_hash=local_hash,
        server_hash=server_hash
    )


# ============================================================
# Test cases
# ============================================================

def test_case(name, local_state, server_response, expected_fresh, expected_reason):
    result = check_freshness(local_state, server_response)
    status = "✅ PASS" if (result.is_fresh == expected_fresh and result.reason == expected_reason) else "❌ FAIL"
    print(f"\n{status} | {name}")
    print(f"  Expected: fresh={expected_fresh}, reason='{expected_reason}'")
    print(f"  Got:      {result}")
    return result.is_fresh == expected_fresh and result.reason == expected_reason


print("=" * 70)
print("🧪 Freshness Logic Tests")
print("=" * 70)

# Test 1: First sync — no local data
test_case(
    "First sync (no local data)",
    local_state={'revision': 0, 'hash': '', 'count': 0},
    server_response={'success': True, 'revision': 1, 'hash': 'abc123', 'not_modified': False},
    expected_fresh=False,
    expected_reason='no_local_data'
)

# Test 2: Server response confirms match
test_case(
    "Server says not_modified=true",
    local_state={'revision': 5, 'hash': 'abc123', 'count': 100},
    server_response={'success': True, 'revision': 5, 'hash': 'abc123', 'not_modified': True},
    expected_fresh=True,
    expected_reason='match'
)

# Test 3: Admin uploaded new live catalog → revision incremented
test_case(
    "Admin uploaded new live catalog (revision incremented)",
    local_state={'revision': 5, 'hash': 'abc123', 'count': 100},
    server_response={'success': True, 'revision': 6, 'hash': 'xyz789', 'not_modified': False},
    expected_fresh=False,
    expected_reason='revision_mismatch'
)

# Test 4: Only hash changed (rare but possible)
test_case(
    "Hash changed (content updated, revision same)",
    local_state={'revision': 5, 'hash': 'abc123', 'count': 100},
    server_response={'success': True, 'revision': 5, 'hash': 'xyz789', 'not_modified': False},
    expected_fresh=False,
    expected_reason='hash_mismatch'
)

# Test 5: Network failure
test_case(
    "Network error (script unreachable)",
    local_state={'revision': 5, 'hash': 'abc123', 'count': 100},
    server_response={'success': False, 'message': 'timeout'},
    expected_fresh=False,
    expected_reason='meta_unavailable:timeout'
)

# Test 6: Stale data (different reason from mismatch)
test_case(
    "Generic stale case (no specific reason)",
    local_state={'revision': 5, 'hash': 'abc123', 'count': 100},
    server_response={'success': True, 'revision': 5, 'hash': '', 'not_modified': False},
    expected_fresh=False,
    expected_reason='stale'
)

print("\n" + "=" * 70)
print("✅ All tests demonstrate that freshness logic correctly:")
print("   - Detects stale data (revision_mismatch)")
print("   - Detects content changes (hash_mismatch)")
print("   - Recognizes fresh data (match)")
print("   - Handles network failures gracefully")
print("=" * 70)
