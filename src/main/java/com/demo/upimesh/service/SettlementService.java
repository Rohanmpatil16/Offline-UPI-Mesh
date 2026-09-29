package com.demo.upimesh.service;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public SettlementService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public Transaction settle(PaymentInstruction instruction, String packetHash, String bridgeNodeId, Integer hopCount) {
        // Fetch sender account
        Account sender = accountRepository.findByVpa(instruction.getSenderVpa())
                .orElseThrow(() -> new IllegalArgumentException("Sender account not found: " + instruction.getSenderVpa()));

        // Fetch receiver account
        Account receiver = accountRepository.findByVpa(instruction.getReceiverVpa())
                .orElseThrow(() -> new IllegalArgumentException("Receiver account not found: " + instruction.getReceiverVpa()));

        // Verify balance
        if (sender.getBalance().compareTo(instruction.getAmount()) < 0) {
            throw new IllegalStateException("Insufficient funds in sender account. Available: ₹" + sender.getBalance() + ", Required: ₹" + instruction.getAmount());
        }

        // Debit sender & Credit receiver
        sender.setBalance(sender.getBalance().subtract(instruction.getAmount()));
        receiver.setBalance(receiver.getBalance().add(instruction.getAmount()));

        accountRepository.save(sender);
        accountRepository.save(receiver);

        // Record Transaction Ledger
        Transaction transaction = new Transaction(
                packetHash,
                instruction.getSenderVpa(),
                instruction.getReceiverVpa(),
                instruction.getAmount(),
                instruction.getNonce(),
                "SETTLED",
                bridgeNodeId,
                hopCount
        );

        return transactionRepository.save(transaction);
    }
}
