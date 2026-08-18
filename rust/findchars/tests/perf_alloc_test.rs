//! PERF-008: No Hot-Path Allocation.
//!
//! A counting global allocator wraps the system allocator and is armed around
//! a single `find()` call. This file must stay its own integration-test binary
//! so the `#[global_allocator]` affects nothing else, and its tests share a
//! lock so the armed window never observes another test's allocations.

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

use findchars::vpa::{FilterLiterals, FilterState};
use findchars::{EngineBuilder, MatchStorage, SimdBackend};

struct CountingAlloc;

static ARMED: AtomicBool = AtomicBool::new(false);
static ALLOCATIONS: AtomicUsize = AtomicUsize::new(0);

unsafe impl GlobalAlloc for CountingAlloc {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        if ARMED.load(Ordering::SeqCst) {
            ALLOCATIONS.fetch_add(1, Ordering::SeqCst);
        }
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        unsafe { System.dealloc(ptr, layout) }
    }

    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        if ARMED.load(Ordering::SeqCst) {
            ALLOCATIONS.fetch_add(1, Ordering::SeqCst);
        }
        unsafe { System.alloc_zeroed(layout) }
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        if ARMED.load(Ordering::SeqCst) {
            ALLOCATIONS.fetch_add(1, Ordering::SeqCst);
        }
        unsafe { System.realloc(ptr, layout, new_size) }
    }
}

#[global_allocator]
static ALLOC: CountingAlloc = CountingAlloc;

static TEST_LOCK: Mutex<()> = Mutex::new(());

/// Runs `f` with the counting allocator armed; returns its result and the
/// number of allocation events (alloc/alloc_zeroed/realloc) observed.
fn armed<T>(f: impl FnOnce() -> T) -> (T, usize) {
    ALLOCATIONS.store(0, Ordering::SeqCst);
    ARMED.store(true, Ordering::SeqCst);
    let out = f();
    ARMED.store(false, Ordering::SeqCst);
    (out, ALLOCATIONS.load(Ordering::SeqCst))
}

/// Allocation-free chunk filter: zeroes every occurrence of the bound literal,
/// exercising the filter path without introducing allocations of its own.
fn suppress_filter(
    acc: &mut [u8],
    _state: &mut FilterState,
    literals: &FilterLiterals,
    len: usize,
) {
    if literals.is_empty() {
        return;
    }
    let lit = literals[0];
    for b in acc[..len].iter_mut() {
        if *b == lit {
            *b = 0;
        }
    }
}

/// 64 KiB with a `*` every 32 bytes and a digit every 64 bytes.
fn test_data() -> Vec<u8> {
    let mut data = vec![b'a'; 64 * 1024];
    for i in (0..data.len()).step_by(32) {
        data[i] = b'*';
    }
    for i in (7..data.len()).step_by(64) {
        data[i] = b'7';
    }
    data
}

/// SIMD backends the host CPU actually supports (same gating as fuzz_parity_test).
fn available_backends() -> Vec<SimdBackend> {
    let mut backends = vec![SimdBackend::Scalar];
    #[cfg(target_arch = "x86_64")]
    {
        if is_x86_feature_detected!("avx2") && is_x86_feature_detected!("ssse3") {
            backends.push(SimdBackend::Avx2);
        }
        if is_x86_feature_detected!("avx512bw")
            && is_x86_feature_detected!("avx512vbmi")
            && is_x86_feature_detected!("avx512vbmi2")
        {
            backends.push(SimdBackend::Avx512);
        }
    }
    #[cfg(target_arch = "aarch64")]
    {
        backends.push(SimdBackend::Neon);
    }
    backends
}

// PERF-008 criteria 1 & 3: a find() on pre-sized storage — including filter
// processing — allocates zero heap objects.
#[test]
fn perf_008_no_hot_path_allocation() {
    let _guard = TEST_LOCK.lock().unwrap();
    let data = test_data();

    for backend in available_backends() {
        let result = EngineBuilder::new()
            .codepoints("star", b"*")
            .range("digits", b'0', b'9')
            .chunk_filter(suppress_filter, &["star"])
            .backend(backend)
            .build()
            .expect("solver failed");

        let mut storage = MatchStorage::new(data.len());
        // Warmup settles any lazy initialization before the armed run.
        let warm_len = result.engine.find(&data, &mut storage).len();
        assert!(warm_len > 0, "{backend:?}: warmup found no matches");

        let (found, allocations) = armed(|| result.engine.find(&data, &mut storage).len());

        assert_eq!(
            found, warm_len,
            "{backend:?}: armed run diverged from warmup"
        );
        assert_eq!(
            allocations, 0,
            "{backend:?}: find() on pre-sized storage must not allocate"
        );
    }
}

// PERF-008 criterion 2: storage auto-grow is the only allocation path, and
// results stay correct when it triggers.
#[test]
fn perf_008_autogrow_is_only_allocation() {
    let _guard = TEST_LOCK.lock().unwrap();
    let data = test_data();

    let result = EngineBuilder::new()
        .codepoints("star", b"*")
        .build()
        .expect("solver failed");

    let mut storage = MatchStorage::new(4);
    let (found, allocations) = armed(|| result.engine.find(&data, &mut storage).len());

    let expected = data.iter().filter(|&&b| b == b'*').count();
    assert_eq!(found, expected, "auto-grow must preserve correctness");
    assert!(
        allocations > 0,
        "undersized storage must grow through the allocator"
    );
}
