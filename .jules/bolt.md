## 2024-05-18 - [Avoid O(N²) array slicing in React render maps]
**Learning:** Found a performance bottleneck where `.slice(0, index).some(...)` was being called inside a `.map` over array elements, causing an O(N²) loop during rendering. React`s render cycle struggles when list rendering logic is nested this way, particularly when handling long lists like conversation messages.
**Action:** Always pre-calculate derived states (e.g., indices of first pending items) with `useMemo` and an O(N) method like `findIndex` before iterating, then just compare indices inside the `.map`.
