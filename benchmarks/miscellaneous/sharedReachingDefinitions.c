/*
 * Features a large number of register uses with a common set of reaching definitions.
 * see https://github.com/hernanponcedeleon/Dat3M/issues/941
 */
#include <assert.h>
#include <dat3m.h>

#define N 100

int main()
{
    int r1 = 0;
    for (int i = 0; i < N+1; i++) {
        if (__VERIFIER_nondet_bool()) { break; }
        r1 += i;
    }

    // Intermediate variable "phi = r1" should act like a phi-node.
    // We need to make the assignment more complex/convoluted so that Dartagnan does not propagate it away.
    // int phi = (r1 + r1) / 2;

    int r2 = 0;
    for (int i = 0; i < N; i++) {
        if (__VERIFIER_nondet_bool()) { break; }
        r2 += r1;
        // r2 += phi;
    }

    assert(r2 <= N*(N*(N+1))/2);
}
