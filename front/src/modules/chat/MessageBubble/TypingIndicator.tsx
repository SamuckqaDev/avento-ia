import { DotsWrapper, Dot } from './TypingIndicatorStyles';

export function TypingIndicator() {
  return (
    <DotsWrapper>
      <Dot $delay="-0.32s" />
      <Dot $delay="-0.16s" />
      <Dot $delay="0s" />
    </DotsWrapper>
  );
}
