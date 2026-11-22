package com.oorja.backend.repository;

import com.oorja.backend.entity.SupportTicket;
import com.oorja.backend.entity.enums.SupportTicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    List<SupportTicket> findByCustomerIdOrderByLastMessageAtDesc(Long customerId);

    List<SupportTicket> findAllByOrderByLastMessageAtDesc();

    List<SupportTicket> findByStatusInOrderByLastMessageAtDesc(List<SupportTicketStatus> statuses);

    Optional<SupportTicket> findByTicketNumber(String ticketNumber);

    long countByStatus(SupportTicketStatus status);

    /** Open tickets nobody has replied to inside the promised window. */
    @Query("""
           select t from SupportTicket t
           where t.firstResponseAt is null
             and t.firstResponseDue < CURRENT_TIMESTAMP
             and t.status in (com.oorja.backend.entity.enums.SupportTicketStatus.OPEN,
                              com.oorja.backend.entity.enums.SupportTicketStatus.IN_PROGRESS)
           order by t.firstResponseDue asc
           """)
    List<SupportTicket> findSlaBreached();

    @Query("select coalesce(max(t.id), 0) from SupportTicket t")
    Long findMaxId();
}
