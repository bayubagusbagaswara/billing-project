        for (Customer customer : customerList) {
            /* Get all important data from customers */
            String customerCode = customer.getCustomerCode();
            String customerName = customer.getCustomerName();
            BigDecimal customerMinimumFee = customer.getCustomerMinimumFee();
            BigDecimal customerSafekeepingFee = customer.getCustomerSafekeepingFee();
            BigDecimal transactionHandlingFee = customer.getCustomerTransactionHandling();
            String billingCategory = customer.getBillingCategory();
            String billingType = customer.getBillingType();
            String billingTemplate = customer.getBillingTemplate();
            String miCode = customer.getMiCode();
            String account = customer.getAccount();
            String accountName = customer.getAccountName();
            String currency = customer.getCurrency();
            List<String> validationErrors = new ArrayList<>();

            try {
                /* get data investment management */
                InvestmentManagementDTO investmentManagementDTO = investmentManagementService.getByCode(miCode);

                /* get data sk transaction */
                List<SkTransaction> skTransactionList = skTransactionService.getAllByAidAndMonthAndYear(customerCode, monthNameMinus1, yearMinus1);

                /* get data rg daily */
                List<SfValRgDaily> sfValRgDailyList = sfValRgDailyService.getAllByAidAndMonthAndYear(customerCode, monthNameMinus1, yearMinus1);

                Optional<BillingCore> existingBillingCore = billingCoreRepository.findByCustomerCodeAndBillingCategoryAndBillingTypeAndMonthAndYear(customerCode, billingCategory, billingType, monthNameMinus1, yearMinus1);
                if (existingBillingCore.isEmpty() || Boolean.TRUE.equals(!existingBillingCore.get().getPaid())) {
                    existingBillingCore.ifPresent(this::deleteExistingBillingCore);
                    /* calculation process */
                    Integer transactionHandlingValueFrequency = calculateTransactionHandlingValueFrequency(customerCode, skTransactionList);
                    BigDecimal transactionHandlingAmountDue = calculateTransactionHandlingAmountDue(customerCode, transactionHandlingFee, transactionHandlingValueFrequency);
                    BigDecimal safekeepingValueFrequency = calculateSafekeepingValueFrequency(customerCode, sfValRgDailyList);
                    BigDecimal safekeepingAmountDue = calculateSafekeepingAmountDue(customerCode, sfValRgDailyList);
                    BigDecimal subTotal = calculateSubTotalAmountDue(customerCode, transactionHandlingAmountDue, safekeepingAmountDue);
                    BigDecimal vatAmountDue = calculateVatAmountDue(customerCode, subTotal, vatFee);
                    BigDecimal totalAmountDue = calculateTotalAmountDue(customerCode, subTotal, vatAmountDue);

                    /* build billing core */
                    BillingCore billingCore = BillingCore.builder()
                            .createdAt(dateNow)
                            .updatedAt(dateNow)
                            .approvalStatus(ApprovalStatus.PENDING)
                            .billingStatus(BillingStatus.GENERATED)
                            .customerCode(customerCode)
                            .customerName(customerName)
                            .month(monthNameMinus1)
                            .year(yearMinus1)
                            .billingPeriod(monthNameMinus1 + " " + yearMinus1)
                            .billingStatementDate(ConvertDateUtil.convertInstantToString(dateNow))
                            .billingPaymentDueDate(ConvertDateUtil.convertInstantToStringPlus14Days(dateNow))
                            .billingCategory(billingCategory)
                            .billingType(billingType)
                            .billingTemplate(billingTemplate)
                            .investmentManagementName(investmentManagementDTO.getName())
                            .investmentManagementAddress1(investmentManagementDTO.getAddress1())
                            .investmentManagementAddress2(investmentManagementDTO.getAddress2())
                            .investmentManagementAddress3(investmentManagementDTO.getAddress3())
                            .investmentManagementAddress4(investmentManagementDTO.getAddress4())
                            .investmentManagementEmail(investmentManagementDTO.getEmail())
                            .investmentManagementUniqueKey(investmentManagementDTO.getUniqueKey())
                            .customerMinimumFee(customerMinimumFee)
                            .customerSafekeepingFee(customerSafekeepingFee)
                            .account(account)
                            .accountName(accountName)
                            .currency(currency)
                            .transactionHandlingValueFrequency(transactionHandlingValueFrequency)
                            .transactionHandlingFee(transactionHandlingFee)
                            .transactionHandlingAmountDue(transactionHandlingAmountDue)
                            .safekeepingValueFrequency(safekeepingValueFrequency)
                            .safekeepingFee(customerSafekeepingFee)
                            .safekeepingAmountDue(safekeepingAmountDue)
                            .subTotal(subTotal)
                            .vatFee(vatFee)
                            .vatAmountDue(vatAmountDue)
                            .totalAmountDue(totalAmountDue)
                            .gefuCreated(false)
                            .paid(false)
                            .build();

                    String number = billingNumberService.generateSingleNumber(monthNameNow, yearNow);
                    billingCore.setBillingNumber(number);
                    billingCoreRepository.save(billingCore);
                    billingNumberService.saveSingleNumber(number);
                    totalDataSuccess++;
                } else {
                    addErrorMessage(errorMessageList, customer.getCustomerCode(), "Billing already paid for period " + monthNameMinus1 + " " + yearMinus1);
                    totalDataFailed++;
                }
            } catch (Exception e) {
                log.error("Error processing customer code {}: {}", customerCode, e.getMessage(), e);
                handleGeneralError(customerCode, e, validationErrors, errorMessageList);
                totalDataFailed++;
            }
        }
        log.info("Total successful calculations: {}, total failed calculations: {}", totalDataSuccess, totalDataFailed);
        return new BillingCalculationResponse(totalDataSuccess, totalDataFailed, errorMessageList);
    }

    private void deleteExistingBillingCore(BillingCore existBillingCore) {
        String billingNumber = existBillingCore.getBillingNumber();
        billingCoreRepository.delete(existBillingCore);
        billingNumberService.deleteByBillingNumber(billingNumber);
    }

    private void addErrorMessage(List<BillingCalculationErrorMessageDTO> calculationErrorMessages, String customerCode, String message) {
        List<String> errorMessages = new ArrayList<>();
        errorMessages.add(message);
        calculationErrorMessages.add(new BillingCalculationErrorMessageDTO(customerCode, errorMessages));
    }