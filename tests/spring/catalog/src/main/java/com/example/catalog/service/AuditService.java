package com.example.catalog.service;

import com.example.catalog.domain.AuditEntry;
import com.example.catalog.mapper.AuditMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 監査ログ。呼び出し元がロールバックしても残るように、別のトランザクションで書く。 */
@Service
public class AuditService {

    private final AuditMapper auditMapper;
    private final Clock clock;

    public AuditService(AuditMapper auditMapper, Clock clock) {
        this.auditMapper = auditMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, @Nullable Long itemId) {
        AuditEntry entry = new AuditEntry();
        entry.setAction(action);
        entry.setItemId(itemId);
        entry.setCreatedAt(LocalDateTime.now(clock));
        auditMapper.insert(entry);
    }

    @Transactional(readOnly = true)
    public long count() {
        return auditMapper.count();
    }
}
