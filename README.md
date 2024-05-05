 private final BillingCustomerRepository customerRepository;
    private final BillingDataChangeRepository dataChangeRepository;
    private final Validator validator;
    private final ObjectMapper objectMapper;
    private final BillingMIRepository investmentManagementRepository;
    private final BillingSellingAgentDataRepository sellingAgentDataRepository;

    @Override
    public boolean isCodeAlreadyExists(String code) {
        return customerRepository.existsByCustomerCode(code);
    }

    @Override
    public CreateCustomerListResponse create(CreateCustomerRequest request, BillingDataChangeDTO dataChangeDTO) {
        log.info("Create single customer with request: {}", request);
        String inputId = request.getInputId();
        String inputIPAddress = request.getInputIPAddress();
        int totalDataSuccess = 0;
        int totalDataFailed = 0;
        List<ErrorMessageCustomerDTO> errorMessageCustomerDTOList = new ArrayList<>();

        try {
            List<String> errorMessages = new ArrayList<>();
            // TODO: masukkan data-data customer yang ingin diinputkan
            CustomerDTO customerDTO = CustomerDTO.builder()
                    .customerCode(request.getCustomerCode())
                    .customerName(request.getCustomerName())
                    .investmentManagementCode(request.getMiCode())
                    .build();

            Errors errors = validateCustomerDTO(customerDTO);
            if (errors.hasErrors()) {
                errors.getAllErrors().forEach(error -> errorMessages.add(error.getDefaultMessage()));
            }

            // Check MI Code apakah ada atau tidak
            Optional<BillingMI> investmentManagementOptional = investmentManagementRepository.findById(Long.valueOf(request.getMiCode()));
            if (!investmentManagementOptional.isPresent()) {
                errorMessages.add("Investment Code not found with code: " + request.getMiCode());
            }

            // If investment management exist, then get data
            BillingMI investmentManagement = investmentManagementOptional.get();

            // set investmentManagementName to object customerDTO
            customerDTO.setInvestmentManagementName(investmentManagement.getName());

            // Check customer code sudah tersedia atau belum
            validationCustomerCodeAlreadyExists(customerDTO, errorMessages);

            if (errorMessages.isEmpty()) {
                BillingDataChange dataChange = getBillingDataChangeCreate(dataChangeDTO, customerDTO, inputId, inputIPAddress);
                dataChangeRepository.save(dataChange);
                totalDataSuccess++;
            } else {
                totalDataFailed = getTotalDataFailed(totalDataFailed, errorMessageCustomerDTOList, customerDTO.getCustomerCode(), errorMessages);
            }

            return new CreateCustomerListResponse(totalDataSuccess, totalDataFailed, errorMessageCustomerDTOList);
        } catch (Exception e) {
            log.error("An error occurred while saving data changes to create customer data: {}", e.getMessage());
            throw new DataChangeException("An error occurred while saving data changes to create customer data", e);
        }
    }

    @Override
    public CreateCustomerListResponse createList(CreateCustomerListRequest request, BillingDataChangeDTO dataChangeDTO) {
        log.info("Create customer list with request: {}", request);
        String inputId = request.getInputId();
        String inputIPAddress = request.getInputIPAddress();
        int totalDataSuccess = 0;
        int totalDataFailed = 0;
        List<ErrorMessageCustomerDTO> errorMessageCustomerDTOList = new ArrayList<>();

        try {
            for (CustomerDTO customerDTO : request.getCustomerDTOList()) {
                List<String> errorMessages = new ArrayList<>();
                Errors errors = validateCustomerDTO(customerDTO);
                if (errors.hasErrors()) {
                    errors.getAllErrors().forEach(error -> errorMessages.add(error.getDefaultMessage()));
                }

                Optional<BillingMI> investmentManagementOptional = investmentManagementRepository.findById(Long.valueOf(customerDTO.getInvestmentManagementCode()));
                if (!investmentManagementOptional.isPresent()) {
                    errorMessages.add("Investment Code not found with code: " + customerDTO.getInvestmentManagementCode());
                }

                // If investment management exist, then get data
                BillingMI investmentManagement = investmentManagementOptional.get();

                // set investmentManagementName to object customerDTO
                customerDTO.setInvestmentManagementName(investmentManagement.getName());

                validationCustomerCodeAlreadyExists(customerDTO, errorMessages);

                // validation semua data enum, billingCategory, billingType, billingTemplate, currency
                // TODO: validation enum billing category
                boolean validateEnumBillingCategory = validateEnumBillingCategory(customerDTO.getBillingCategory());
                if (!validateEnumBillingCategory) {
                    errorMessages.add("Billing Category enum value '" + customerDTO.getBillingCategory() + "' not found");
                }

                // TODO: validation enum billing type
                boolean validateEnumBillingType = validateEnumBillingType(customerDTO.getBillingType());
                if (!validateEnumBillingType) {
                    errorMessages.add("Billing Type enum value '" + customerDTO.getBillingType() + "' not found");
                }

                // TODO: validation enum billing template
                boolean validateEnumBillingTemplate = validateEnumBillingTemplate(customerDTO.getBillingTemplate());
                if (!validateEnumBillingTemplate) {
                    errorMessages.add("Billing Template enum value '" + customerDTO.getBillingTemplate() + "' not found");
                }

                // TODO: validation enum currency
                boolean validateEnumCurrency = validateEnumCurrency(customerDTO.getCurrency());
                if (!validateEnumCurrency) {
                    errorMessages.add("Currency enum value '" + customerDTO.getCurrency() + "' not found");
                }

                // check exist selling agent code
                Optional<BillingSellingAgentData> sellingAgentOptional = sellingAgentDataRepository.findByBillingSellingAgentCode(customerDTO.getSellingAgentCode());
                if (!sellingAgentOptional.isPresent()) {
                    errorMessages.add("Selling Agent not found with code: " + customerDTO.getSellingAgentCode());
                }

                BillingSellingAgentData billingSellingAgentData = sellingAgentOptional.get();
                customerDTO.setSellingAgentCode(billingSellingAgentData.getBillingSellingAgentCode());

                if (errorMessages.isEmpty()) {
                    BillingDataChange dataChange = getBillingDataChangeCreate(dataChangeDTO, customerDTO, inputId, inputIPAddress);
                    dataChangeRepository.save(dataChange);
                    totalDataSuccess++;
                } else {
                    totalDataFailed = getTotalDataFailed(totalDataFailed, errorMessageCustomerDTOList, customerDTO.getCustomerCode(), errorMessages);
                }
            }
            return new CreateCustomerListResponse(totalDataSuccess, totalDataFailed, errorMessageCustomerDTOList);
        } catch (Exception e) {
            log.error("An error occurred while saving data changes to create customer list data: {}", e.getMessage());
            throw new DataChangeException("An error occurred while saving data changes to create customer list data", e);
        }
    }

    @Override
    public CreateCustomerListResponse createListApprove(CreateCustomerListRequest request) {
        log.info("Create customer list approve with request: {}", request);
        String approveId = request.getApproveId();
        String approveIPAddress = request.getApproveIPAddress();
        int totalDataSuccess = 0;
        int totalDataFailed = 0;
        List<ErrorMessageCustomerDTO> errorMessageCustomerDTOList = new ArrayList<>();

        try {
            // TODO: Jika dataChangeId tidak ditemukan, maka langsung lempar exception saja. Data single maupun list
            List<Long> dataChangeIdList = request.getCustomerDTOList().stream().map(CustomerDTO::getDataChangeId)
                    .collect(Collectors.toList());
            Integer totalDataChangeIdList = dataChangeIdList.size();
            Boolean existsByIdList = dataChangeRepository.existsByIdList(dataChangeIdList, totalDataChangeIdList);
            if (!existsByIdList) {
                throw new IllegalArgumentException("Data Change not found");
            }

            for (CustomerDTO customerDTO : request.getCustomerDTOList()) {
                List<String> errorMessages = new ArrayList<>();
                Errors errors = validateCustomerDTO(customerDTO);
                if (errors.hasErrors()) {
                    errors.getAllErrors().forEach(error -> errorMessages.add(error.getDefaultMessage()));
                }

                Optional<BillingMI> investmentManagementOptional = investmentManagementRepository.findById(Long.valueOf(customerDTO.getInvestmentManagementCode()));
                if (!investmentManagementOptional.isPresent()) {
                    errorMessages.add("Investment Code not found with code: " + customerDTO.getInvestmentManagementCode());
                }
                BillingMI investmentManagement = investmentManagementOptional.get();

                // validasi untuk customer code sudah tersedia atau belum
                validationCustomerCodeAlreadyExists(customerDTO, errorMessages);

                // validasi untuk semua data enum dari Billing Customer
                // Billing Category, Billing Type, Billing Template, Currency, Selling Agent

                // ambil data Data Change by id
                BillingDataChange dataChange = getBillingDataChangeById(customerDTO.getDataChangeId());

                if (errorMessages.isEmpty()) {
                    BillingCustomer billingCustomer = BillingCustomer.builder()
                            .customerCode(customerDTO.getCustomerCode())
                            .customerName(customerDTO.getCustomerName())
                            .miCode(investmentManagement.getCode())
                            .miName(investmentManagement.getName())
                            .build();
                    BillingCustomer billingCustomerSaved = customerRepository.save(billingCustomer);

                    // update data change to APPROVED
                    String jsonDataAfter = objectMapper.writeValueAsString(billingCustomerSaved);
                    dataChange.setApprovalStatus(ApprovalStatus.Approved);
                    dataChange.setApproverId(approveId);
                    dataChange.setApproverIPAddress(approveIPAddress);
                    dataChange.setApproveDate(new Date());
                    dataChange.setEntityId(billingCustomerSaved.getId().toString());
                    dataChange.setJsonDataAfter(jsonDataAfter);
                    dataChange.setDescription("Successfully approve data change and save data entity to table");
                    dataChangeRepository.save(dataChange);
                    totalDataSuccess++;
                } else {
                    // update data change to REJECTED
                    dataChange.setApprovalStatus(ApprovalStatus.Rejected);
                    dataChange.setApproverId(approveId);
                    dataChange.setApproverIPAddress(approveIPAddress);
                    dataChange.setApproveDate(new Date());
                    dataChange.setDescription(StringUtil.joinStrings(errorMessages));
                    dataChangeRepository.save(dataChange);
                    totalDataFailed++;
                }
            }
            // update data change and save to entity
            return new CreateCustomerListResponse(totalDataSuccess, totalDataFailed, errorMessageCustomerDTOList);
        } catch (Exception e) {
            log.error("An error occurred while saving entity data billing customers: {}", e.getMessage());
            throw new DataProcessingException("An error occurred while saving entity data billing customers", e);
        }
    }

    @Override
    public UpdateCustomerListResponse update(UpdateCustomerRequest updateCustomerRequest, BillingDataChangeDTO dataChangeDTO) {
        log.info("Update customer by id with request: {}", updateCustomerRequest);
        String inputId = updateCustomerRequest.getInputId();
        String inputIPAddress = updateCustomerRequest.getInputIPAddress();
        int totalDataSuccess = 0;
        int totalDataFailed = 0;
        List<ErrorMessageCustomerDTO> errorMessageCustomerDTOList = new ArrayList<>();

        try {
            List<String> errorMessages = new ArrayList<>();

            // TODO: Mapping data customer from object updateCustomerRequest to object customerDTO
            CustomerDTO customerDTO = CustomerDTO.builder()
                    .id(updateCustomerRequest.getId())
                    .customerCode(updateCustomerRequest.getCustomerCode())
                    .investmentManagementCode(updateCustomerRequest.getInvestmentManagementCode())
                    .billingCategory(updateCustomerRequest.getBillingCategory())
                    .billingType(updateCustomerRequest.getBillingType())
                    .billingTemplate(updateCustomerRequest.getBillingTemplate())
                    .sellingAgentCode(updateCustomerRequest.getSellingAgentCode())
                    .build();

            // validation untuk field data kosong atau tidak (HARUSNYA disini juga dicek field tersebut hanya bisa NUMERIC dan ALPHABET, gak boleh spesial character)
            Errors errors = validateCustomerDTO(customerDTO);
            if (errors.hasErrors()) {
                errors.getAllErrors().forEach(error -> errorMessages.add(error.getDefaultMessage()));
            }

            BillingCustomer billingCustomer = customerRepository.findById(customerDTO.getId())
                    .orElseThrow(() -> new DataNotFoundException("Customer not found with id: " + customerDTO.getId()));

            if (errorMessages.isEmpty()) {
                String jsonDataBefore = objectMapper.writeValueAsString(billingCustomer);
                String jsonDataAfter = objectMapper.writeValueAsString(customerDTO);
                BillingDataChange dataChange = BillingDataChange.builder()
                        .approvalStatus(ApprovalStatus.Pending)
                        .inputerId(inputId)
                        .inputDate(new Date())
                        .inputerIPAddress(inputIPAddress)
                        .approverId("")
                        .approveDate(null)
                        .approverIPAddress("")
                        .action(ChangeAction.Edit)
                        .entityId(billingCustomer.getId().toString())
                        .entityClassName(BillingCustomer.class.getName())
                        .tableName(TableNameResolver.getTableName(BillingCustomer.class))
                        .jsonDataBefore(jsonDataBefore)
                        .jsonDataAfter(jsonDataAfter)
                        .methodHttp(dataChangeDTO.getMethodHttp())
                        .endpoint(dataChangeDTO.getEndpoint())
                        .isRequestBody(dataChangeDTO.isRequestBody())
                        .isRequestParam(dataChangeDTO.isRequestParam())
                        .isPathVariable(dataChangeDTO.isPathVariable())
                        .menu(dataChangeDTO.getMenu())
                        .build();
                dataChangeRepository.save(dataChange);
                totalDataSuccess++;
            } else {
                totalDataFailed = getTotalDataFailed(totalDataFailed, errorMessageCustomerDTOList, customerDTO.getCustomerCode(), errorMessages);
            }
            return new UpdateCustomerListResponse(totalDataSuccess, totalDataFailed, errorMessageCustomerDTOList);
        } catch (Exception e) {
            log.error("An error occurred while updating entity data billing customer: {}", e.getMessage());
            throw new DataChangeException("An error occurred while updating entity data billing customer", e);
        }
    }

    @Override
    public UpdateCustomerListResponse updateList(UpdateCustomerListRequest updateCustomerListRequest, BillingDataChangeDTO dataChangeDTO) {
        log.info("Update billing customer list with request: {}", updateCustomerListRequest);
        String inputId = updateCustomerListRequest.getInputId();
        String inputIPAddress = updateCustomerListRequest.getInputIPAddress();
        int totalDataSuccess = 0;
        int totalDataFailed = 0;
        List<ErrorMessageCustomerDTO> errorMessageCustomerDTOList = new ArrayList<>();

        try {
            for (CustomerDTO customerDTO : updateCustomerListRequest.getCustomerDTOList()) {
                List<String> errorMessages = new ArrayList<>();

                Errors errors = validateCustomerDTO(customerDTO);
                if (errors.hasErrors()) {
                    errors.getAllErrors().forEach(error -> errorMessages.add(error.getDefaultMessage()));
                }

                BillingCustomer billingCustomer = customerRepository.findById(customerDTO.getId()).orElseThrow(() -> new DataNotFoundException("Customer not found with id: " + customerDTO.getId()));

                // cek error message list
                if (errorMessages.isEmpty()) {
                    String jsonDataBefore = objectMapper.writeValueAsString(billingCustomer);
                    String jsonDataAfter = objectMapper.writeValueAsString(customerDTO);
                    // create data change
                    BillingDataChange dataChange = BillingDataChange.builder()
                            .approvalStatus(ApprovalStatus.Pending)
                            .inputerId(inputId)
                            .inputDate(new Date())
                            .inputerIPAddress(inputIPAddress)
                            .approverId("")
                            .approveDate(new Date())
                            .approverIPAddress("")
                            .action(ChangeAction.Edit)
                            .entityId(billingCustomer.getId().toString())
                            .entityClassName(BillingCustomer.class.getName())
                            .tableName(TableNameResolver.getTableName(BillingCustomer.class))
                            .jsonDataBefore(jsonDataBefore)
                            .jsonDataAfter(jsonDataAfter)
                            .description("")
                            .methodHttp(dataChangeDTO.getMethodHttp())
                            .endpoint(dataChangeDTO.getEndpoint())
                            .isRequestBody(dataChangeDTO.isRequestBody())
                            .isRequestParam(dataChangeDTO.isRequestParam())
                            .isPathVariable(dataChangeDTO.isPathVariable())
                            .menu(dataChangeDTO.getMenu())
                            .build();
                    dataChangeRepository.save(dataChange);
                    totalDataSuccess++;
                } else {
                    totalDataFailed++;
                }
            }
            return new UpdateCustomerListResponse(totalDataSuccess, totalDataFailed, errorMessageCustomerDTOList);
        } catch (Exception e) {
            log.error("An error occurred while updating data changes to update billing customer list data: {}", e.getMessage());
            throw new DataChangeException("An error occurred while updating data changes to update billing customer list data", e);
        }
    }

    @Override
    public UpdateCustomerListResponse updateApprove(UpdateCustomerListRequest updateCustomerListRequest) {
        log.info("Update approve billing customer with request: {}", updateCustomerListRequest);
        String approveId = updateCustomerListRequest.getApproveId();
        String approveIPAddress = updateCustomerListRequest.getApproveIPAddress();
        int totalDataSuccess = 0;
        int totalDataFailed = 0;
        List<ErrorMessageCustomerDTO> errorMessageCustomerDTOList = new ArrayList<>();

        try {
            // check all data change id
            List<Long> dataChangeIdList = updateCustomerListRequest.getCustomerDTOList().stream().map(CustomerDTO::getDataChangeId).collect(Collectors.toList());
            int totalDataChangeIdList = dataChangeIdList.size();
            Boolean existsByIdList = dataChangeRepository.existsByIdList(dataChangeIdList, totalDataChangeIdList);
            if (!existsByIdList) {
                throw new IllegalArgumentException("ID Data Change not found");
            }

            for (CustomerDTO customerDTO : updateCustomerListRequest.getCustomerDTOList()) {
                List<String> errorMessages = new ArrayList<>();
                Errors errors = validateCustomerDTO(customerDTO);

                if (errors.hasErrors()) {
                    errors.getAllErrors().forEach(error -> errorMessages.add(error.getDefaultMessage()));
                }

                // TODO: pengecekan dan validasi data request yang berasal dari data change

                // TODO: get data change by id
                BillingDataChange dataChange = getBillingDataChangeById(customerDTO.getDataChangeId());

                // TODO:  get billing customer by id
                BillingCustomer billingCustomer = customerRepository.findById(customerDTO.getId())
                        .orElseThrow(() -> new DataNotFoundException("Billing Customer not found: " + customerDTO.getId()));

                if (errorMessages.isEmpty()) {
                    // TODO: update data billing customer, field belum semua diset
                    billingCustomer.setCustomerCode(customerDTO.getCustomerCode());
                    billingCustomer.setCustomerName(customerDTO.getCustomerName());

                    String jsonDataAfter = objectMapper.writeValueAsString(customerDTO);

                    // update data change
                    dataChange.setApprovalStatus(ApprovalStatus.Approved);
                    dataChange.setApproverId(approveId);
                    dataChange.setApproveDate(new Date());
                    dataChange.setApproverIPAddress(approveIPAddress);
                    dataChange.setJsonDataAfter(jsonDataAfter);
                    dataChange.setDescription("Successfully approve data change and update data entity");
                    dataChangeRepository.save(dataChange);
                    totalDataSuccess++;
                } else {
                    String jsonDataAfter = objectMapper.writeValueAsString(customerDTO);
                    dataChange.setApprovalStatus(ApprovalStatus.Rejected);
                    dataChange.setApproverId(approveId);
                    dataChange.setApproveDate(new Date());
                    dataChange.setApproverIPAddress(approveIPAddress);
                    dataChange.setJsonDataAfter(jsonDataAfter);
                    dataChange.setDescription(StringUtil.joinStrings(errorMessages));
                    dataChangeRepository.save(dataChange);
                    totalDataFailed++;
                }
            }

            return new UpdateCustomerListResponse(totalDataSuccess, totalDataFailed, errorMessageCustomerDTOList);
        } catch (Exception e) {
            log.error("An error occurred while updating entity data billing customers: {}", e.getMessage());
            throw new DataProcessingException("An error occurred while updating entity data billing customers", e);
        }
    }

    // delete request to data change

    @Override
    public DeleteCustomerListResponse deleteById(DeleteCustomerRequest deleteCustomerRequest, BillingDataChangeDTO dataChangeDTO) {
        log.info("Delete billing customer by id with request: {}", deleteCustomerRequest);
        String inputId = deleteCustomerRequest.getInputId();
        String inputIPAddress = deleteCustomerRequest.getInputIPAddress();
        int totalDataSuccess = 0;
        int totalDataFailed = 0;
        List<ErrorMessageCustomerDTO> errorMessageCustomerDTOList = new ArrayList<>();

        try {
            List<String> errorMessages = new ArrayList<>();

            BillingCustomer billingCustomer = customerRepository.findById(deleteCustomerRequest.getId())
                    .orElse(null);

            if (billingCustomer == null) {
                errorMessages.add("Billing Customer not found with id: " + deleteCustomerRequest.getId());
            }

            if (errorMessages.isEmpty() && billingCustomer != null) {
                String jsonDataBefore = objectMapper.writeValueAsString(billingCustomer);
                BillingDataChange dataChange = BillingDataChange.builder()
                        .approvalStatus(ApprovalStatus.Pending)
                        .inputerId(inputId)
                        .inputDate(new Date())
                        .inputerIPAddress(inputIPAddress)
                        .approverId("")
                        .approveDate(null)
                        .approverIPAddress("")
                        .action(ChangeAction.Delete)
                        .entityId(billingCustomer.getId().toString())
                        .entityClassName(BillingCustomer.class.getName())
                        .tableName(TableNameResolver.getTableName(BillingCustomer.class))
                        .jsonDataBefore(jsonDataBefore)
                        .jsonDataAfter("")
                        .description("")
                        .methodHttp(dataChangeDTO.getMethodHttp())
                        .endpoint(dataChangeDTO.getEndpoint())
                        .isRequestBody(dataChangeDTO.isRequestBody())
                        .isRequestParam(dataChangeDTO.isRequestParam())
                        .isPathVariable(dataChangeDTO.isPathVariable())
                        .menu(dataChangeDTO.getMenu())
                        .build();

                dataChangeRepository.save(dataChange);
                totalDataSuccess++;
            } else {
                totalDataFailed = getTotalDataFailed(totalDataFailed, errorMessageCustomerDTOList, deleteCustomerRequest.getId().toString(), errorMessages);
            }

            return new DeleteCustomerListResponse(totalDataSuccess, totalDataFailed, errorMessageCustomerDTOList);
        } catch (Exception e) {
            log.error("An error occurred while saving data changes to delete billing customer by id: {}", e.getMessage());
            throw new DataChangeException("An error occurred while saving data changes to delete billing customer by id", e);
        }
    }

    @Override
    public DeleteCustomerListResponse deleteApprove(DeleteCustomerListRequest deleteCustomerListRequest) {
        log.info("Delete approve billing customer with request: {}", deleteCustomerListRequest);
        String approveId = deleteCustomerListRequest.getApproveId();
        String approveIPAddress = deleteCustomerListRequest.getApproveIPAddress();
        int totalDataSuccess = 0;
        int totalDataFailed = 0;
        List<ErrorMessageCustomerDTO> errorMessageCustomerDTOList = new ArrayList<>();

        try {
            // check semua data id data change
            List<Long> dataChangeIdList = deleteCustomerListRequest.getCustomerDTOList().stream().map(CustomerDTO::getDataChangeId)
                    .collect(Collectors.toList());
            Integer totalDataChangeIdList = dataChangeIdList.size();
            Boolean existsByIdList = dataChangeRepository.existsByIdList(dataChangeIdList, totalDataChangeIdList);
            if (!existsByIdList) {
                throw new IllegalArgumentException("ID Data Change not found");
            }

            for (CustomerDTO customerDTO : deleteCustomerListRequest.getCustomerDTOList()) {
                List<String> errorMessages = new ArrayList<>();

                BillingCustomer billingCustomer = customerRepository.findById(customerDTO.getId()).orElse(null);

                if (billingCustomer == null) {
                    errorMessages.add("Billing Customer not found with id: " + customerDTO.getId());
                }

                // get billing data change
                BillingDataChange dataChange = getBillingDataChangeById(customerDTO.getDataChangeId());

                if (errorMessages.isEmpty()) {
                    customerRepository.delete(billingCustomer);

                    dataChange.setApprovalStatus(ApprovalStatus.Approved);
                    dataChange.setApproverId(approveId);
                    dataChange.setApproveDate(new Date());
                    dataChange.setApproverIPAddress(approveIPAddress);
                    dataChange.setJsonDataAfter("");
                    dataChange.setDescription("Successfully approve data change and delete data entity");

                    dataChangeRepository.save(dataChange);
                    totalDataSuccess++;
                } else {
                    String jsonDataAfter = objectMapper.writeValueAsString(customerDTO);
                    dataChange.setApprovalStatus(ApprovalStatus.Rejected);
                    dataChange.setApproverId(approveId);
                    dataChange.setApproveDate(new Date());
                    dataChange.setApproverIPAddress(approveIPAddress);
                    dataChange.setJsonDataAfter(jsonDataAfter);
                    dataChange.setDescription(StringUtil.joinStrings(errorMessages));
                    dataChangeRepository.save(dataChange);
                    totalDataFailed++;
                }
            }
            return new DeleteCustomerListResponse(totalDataSuccess, totalDataFailed, errorMessageCustomerDTOList);
        } catch (Exception e) {
            log.error("An error occurred while deleting entity data billing customer: {}", e.getMessage());
            throw new DataProcessingException("An error occurred while deleting entity data billing customer", e);
        }
    }

    private BillingDataChange getBillingDataChangeCreate(BillingDataChangeDTO dataChangeDTO, CustomerDTO customerDTO, String inputId, String inputIPAddress) throws JsonProcessingException {
        String jsonDataAfter = objectMapper.writeValueAsString(customerDTO);
        return BillingDataChange.builder()
                .approvalStatus(ApprovalStatus.Pending)
                .inputerId(inputId)
                .inputDate(new Date())
                .inputerIPAddress(inputIPAddress)
                .approverId("")
                .approveDate(null)
                .approverIPAddress("")
                .action(ChangeAction.Add)
                .entityId("")
                .entityClassName(BillingCustomer.class.getName())
                .tableName(TableNameResolver.getTableName(BillingCustomer.class))
                .jsonDataBefore("")
                .jsonDataAfter(jsonDataAfter)
                .description("")
                .methodHttp(dataChangeDTO.getMethodHttp())
                .endpoint(dataChangeDTO.getEndpoint())
                .isRequestBody(dataChangeDTO.isRequestBody())
                .isRequestParam(dataChangeDTO.isRequestParam())
                .isPathVariable(dataChangeDTO.isPathVariable())
                .menu(dataChangeDTO.getMenu())
                .build();
    }

    private Errors validateCustomerDTO(CustomerDTO dto) {
        Errors errors = new BeanPropertyBindingResult(dto, "customerDTO");
        validator.validate(dto, errors);
        return errors;
    }

    private void validationCustomerCodeAlreadyExists(CustomerDTO dto, List<String> errorMessages) {
        if (isCodeAlreadyExists(dto.getCustomerCode())) {
            errorMessages.add("Customer code already taken with code: " + dto.getCustomerCode());
        }
    }

    private int getTotalDataFailed(int totalDataFailed, List<ErrorMessageCustomerDTO> errorMessageCustomerDTOList, String customerCode, List<String> errorMessages) {
        ErrorMessageCustomerDTO errorMessageCustomerDTO = new ErrorMessageCustomerDTO();
        errorMessageCustomerDTO.setCode(customerCode);
        errorMessageCustomerDTO.setErrorMessages(errorMessages);
        errorMessageCustomerDTOList.add(errorMessageCustomerDTO);
        totalDataFailed++;
        return totalDataFailed;
    }

    private boolean validateEnumBillingCategory(String billingCategory) {
        boolean categoryFound = false;
        for (BillingCategory value : BillingCategory.values()) {
            if (value.name().equals(billingCategory)) {
                categoryFound = true;
                // Process data based on the matched BillingCategory value
                break;
            }
        }
        return categoryFound;
    }

    private boolean validateEnumBillingType(String billingType) {
        boolean typeFound = false;
        for (BillingType value : BillingType.values()) {
            if (value.name().equals(billingType)) {
                typeFound = true;
                break;
            }
        }
        return typeFound;
    }

    private boolean validateEnumBillingTemplate(String billingTemplate) {
        boolean templateFound = false;
        for (BillingTemplate value : BillingTemplate.values()) {
            if (value.name().equals(billingTemplate)) {
                templateFound = true;
                break;
            }
        }
        return templateFound;
    }

    private boolean validateEnumCurrency(String currency) {
        boolean currencyFound = false;
        for (Currency value : Currency.values()) {
            if (value.name().equals(currency)) {
                currencyFound = true;
                break;
            }
        }
        return currencyFound;
    }

    private BillingDataChange getBillingDataChangeById(Long dataChangeId) {
        return dataChangeRepository.findById(dataChangeId).orElse(null);
    }
