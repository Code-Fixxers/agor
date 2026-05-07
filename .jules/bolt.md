## 2024-05-18 - Memoizing List Items
**Learning:** In a chat application, rendering long lists of messages can cause significant performance bottlenecks if every message re-renders when a new one is added or when the parent component's state changes. Un-memoized complex components like `MessageBlock` in `apps/agor-ui/src/components/MessageBlock/MessageBlock.tsx` are prime candidates for optimization.
**Action:** Always wrap list item components that receive largely static props (like individual messages) with `React.memo` to prevent unnecessary re-renders during chat updates or scrolling.
