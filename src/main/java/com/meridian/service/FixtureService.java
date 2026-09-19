package com.meridian.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.domain.*;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class FixtureService {
    private final ObjectMapper objectMapper;
    private BankState cachedInitialState;
    private List<Recipient> cachedRecipients;
    private List<Provider> cachedProviders;

    public FixtureService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        loadFixture();
    }

    private void loadFixture() {
        try {
            InputStream resourceStream = getClass().getResourceAsStream("/fixture.json");
            if (resourceStream == null) {
                throw new RuntimeException("fixture.json not found in classpath");
            }
            Map<String, Object> fixture = objectMapper.readValue(resourceStream, Map.class);

            Map<String, Object> stateMap = (Map<String, Object>) fixture.get("state");
            cachedInitialState = objectMapper.convertValue(stateMap, BankState.class);

            List<Map<String, Object>> recipientsMaps = (List<Map<String, Object>>) fixture.get("recipients");
            cachedRecipients = new ArrayList<>();
            for (Map<String, Object> map : recipientsMaps) {
                cachedRecipients.add(objectMapper.convertValue(map, Recipient.class));
            }

            List<Map<String, Object>> providersMaps = (List<Map<String, Object>>) fixture.get("providers");
            cachedProviders = new ArrayList<>();
            for (Map<String, Object> map : providersMaps) {
                cachedProviders.add(objectMapper.convertValue(map, Provider.class));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load fixture.json", e);
        }
    }

    public BankState loadInitialState() {
        BankState state = new BankState();
        state.setVersion(cachedInitialState.getVersion());
        state.setBalance(cachedInitialState.getBalance());
        
        // Deep copy transactions
        List<Transaction> txns = new ArrayList<>();
        for (Transaction txn : cachedInitialState.getTransactions()) {
            txns.add(new Transaction(txn.getId(), txn.getReference(), txn.getRecipientId(),
                txn.getName(), txn.getCategory(), txn.getAmount(), txn.getDate(),
                txn.getProvider(), txn.getMethod(), txn.getStatus(), txn.getNote()));
        }
        state.setTransactions(txns);

        // Deep copy budgets
        List<Budget> budgets = new ArrayList<>();
        for (Budget b : cachedInitialState.getBudgets()) {
            budgets.add(new Budget(b.getCategory(), b.getLimit()));
        }
        state.setBudgets(budgets);

        return state;
    }

    public List<Recipient> getRecipients() {
        return new ArrayList<>(cachedRecipients);
    }

    public List<Provider> getProviders() {
        return new ArrayList<>(cachedProviders);
    }

    public Recipient getRecipient(String recipientId) {
        return cachedRecipients.stream()
            .filter(r -> r.getId().equals(recipientId))
            .findFirst()
            .orElse(null);
    }

    public String getDemoDate() {
        return "2026-09-18";
    }
}
