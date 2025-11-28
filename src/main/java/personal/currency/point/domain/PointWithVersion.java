package personal.currency.point.domain;

import jakarta.persistence.*;

/**
 * Optimistic Lock 전략을 위한 Point 엔티티
 * @Version 어노테이션을 통해 낙관적 락 구현
 */
@Entity
@Table(name = "point_with_version")
public class PointWithVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long balance;

    @Version
    @Column(nullable = false)
    private long version;

    protected PointWithVersion() {
    }

    public PointWithVersion(long balance) {
        this.balance = balance;
    }

    public PointWithVersion(Long id, long balance) {
        this.id = id;
        this.balance = balance;
    }

    public Long getId() {
        return id;
    }

    public long getBalance() {
        return balance;
    }

    public void setBalance(long balance) {
        this.balance = balance;
    }

    public long getVersion() {
        return version;
    }
}
