package grammarextractor;

public class Pair {
    public final int first;
    public final int second;

    public Pair(int first, int second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Pair pair)) return false;
        return first == pair.first && second == pair.second;
    }

    @Override
    public int hashCode() {
        return 31 * (31 + first) + second;
    }

    @Override
    public String toString() {
        return "(" + first + ", " + second + ")";
    }
}
