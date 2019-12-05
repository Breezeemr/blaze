(ns blaze.dev
  (:require
    [blaze.spec]
    [blaze.system :as system]
    [clojure.repl :refer [pst]]
    [clojure.spec.test.alpha :as st]
    [clojure.tools.namespace.repl :refer [refresh]]
    [datomic-spec.test :as dst]))


;; Spec Instrumentation
(st/instrument)
(dst/instrument)


(defonce system nil)


(defn init []
  (alter-var-root #'system (constantly (system/init! (System/getenv))))
  nil)


(defn reset []
  (some-> system system/shutdown!)
  (refresh :after `init))


;; Init Development
(comment
  (init)
  (pst)
  )


;; Reset after making changes
(comment
  (reset)
  (st/unstrument)
  )



(def d [{:fhir.Resource/id #uuid "557f4d82-5c42-4334-807b-b4dd97730a6d",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "55a3f601-2353-4883-9261-4db28248e24f",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "55b6b7a6-ebf2-4d5a-953c-39e27a323a89",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "55b6b7b1-ad96-4f8d-861f-2d719334d43b",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "55bbf1a9-8ef9-4698-97ef-7d1f5c08bd81",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "55d77afa-61ad-4aa8-aeab-20bd6260781c",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "55dd1feb-3a0a-48ff-a3d8-785ec56006b2",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "55dd2007-d2bb-4815-9e6c-0584caa8d244",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "55e0d2b0-bb71-4377-9da5-207e0df195e8",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "55e8da18-e1be-4b14-8211-2b197b71929a",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "55ea1384-8560-42f1-84d0-849cf70ce10e",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "55eed738-c3af-4f55-8935-b1efc266c440",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "55f20fb5-db66-4639-9923-12223660d160",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "55f289e6-865f-4586-b469-81c0ec3ce78d",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "55f2e7ed-341d-4516-a6a4-a129622d37b5",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "55f9c1ec-881e-4fe6-a5cd-92dcffa28f6d",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "55fc9118-612c-40b1-97d6-35d5626b26cc",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "55fc9182-ca29-46ff-96f3-c5215fde8c64",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "55fcb1fd-ce75-48f6-88e1-fff046deed98",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "55fcb257-6f5d-4eeb-8d62-d4b42cacbc66",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "55fcb2af-66f1-4bd3-bea5-0f51c1d0167d",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "55fcb398-434a-49da-9489-beb112e170e3",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "5615496b-da30-4d6a-9aad-cfd06f626fbb",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "561ec5e2-8e7d-4226-83ab-b9832c2e189a",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5626a05b-546b-4352-be54-5f00ee646686",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5626a0ba-a7f1-4219-ac70-992eb33ce45c",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "562928b8-1acf-41ba-8625-7fcce63af44c",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "562c5ebb-5175-45e6-a9cb-008282433685",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "562e8d19-3675-4b0a-b408-6e50b72f2647",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "562e9144-03ec-4d2c-a1de-8bb4c9790ea0",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "562e9178-7abd-4b4e-b803-78657773d4f8",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "562e9b7c-fb25-4eb9-92b4-bdcf1d403ecf",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "562e9bad-00c0-4bea-af03-66559dcf5d1c",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "562ea1c2-4711-4b69-abf8-1bd9f92de63d",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "562ea1c2-02a1-4eac-9539-543450130040",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "56312fb7-2688-4d40-a57d-14a03285879d",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "56313007-32d9-48dc-8226-95113bb8a663",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "5631446c-8936-40d1-ba37-28fbfeb65858",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "563298ba-d851-4b06-936c-427112f3f224",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "56329b04-c90d-4064-85ad-0b2ad51dcfae",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "56329b27-c1a6-4110-9e2e-d986083312eb",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "5633ca85-322e-4ac0-824e-8cfc8c714202",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "56452bca-b124-4128-a95d-3976cb4cad2d",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "564e26cf-a1d9-46c0-8085-54376f33832b",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "56609917-af97-4c5d-b6b2-a346e11b5125",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5660a49e-8c06-4150-a185-de589956bca7",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "5666feb7-61e0-44b6-b8d4-e5d76f7a8584",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5666feb7-12a8-41b5-a326-98bb667a174e",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "566b192f-32e8-4f00-a57b-4868997f8199",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "571e98e8-6d84-4fc3-8baa-fcac5eaaf7db",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "571ff730-5722-4047-a81b-ea0e25f69624",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "57293056-f028-4494-a5d0-870e89c2e395",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "572bb3a9-4bad-4ba6-9853-92001dd473b3",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "57308e5f-fd8b-4f29-9702-a70d8384827b",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "57308f07-1f8f-47fc-9ef6-e8082c74149d",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "57310401-f455-4211-a49b-b20ec431e766",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "57354c8f-5c68-42ae-bb3d-6e661aa05749",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "57354d35-ac6f-4f0e-be95-48a278942dbd",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5736667d-67a7-49bd-a7e1-f9fdffef0519",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "575b0582-455b-4606-a2d9-487200d9c32e",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "577d4f02-2701-43da-a112-fb7d18f3966c",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "577d4f08-b4df-4c5d-826e-ee3e55aad94c",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "577d5488-3d84-4b59-9c68-86213ecbf67b",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "577d571f-2455-4eb7-8f6b-794ef495b6a1",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "57896083-0b75-4dc1-8916-ac9c1866525f",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "57e971f2-fd77-4803-a47c-bae2b80211f5",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "57e9721b-e357-45e1-ad47-9caaafc7bcb5",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "588934a7-002e-4e05-b36d-3dbbceac15b1",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "58b7cdd9-164a-4b02-aeff-b12d099c8c5d",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "58b7cdd9-4a89-4690-a783-385b8e9e324e",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "58c07383-387d-47f4-a21d-22426d16a2d5",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5907629c-d6e9-467a-a2a7-afcfc37bec09",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "5908fe9c-61e9-4e3c-a79c-7e09a2b3ba76",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5913696a-6104-4e13-8f6b-b2397af6a768",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "591614f1-f446-4a0f-ab8a-a3f3e8500052",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "591614f1-9bd9-4e69-8181-ae360db6da9d",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "59404b6f-b19d-4beb-ad2a-639809c63128",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "594bdd82-6b85-44d5-a5cb-2d3330168dcc",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "594bde55-6c61-438d-801d-f14b9530f165",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "59b03227-5d3c-466c-b619-767f58f0b4b9",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "59b03227-979c-4224-89bd-43877b34e3ad",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "59b0322d-b86b-48b3-8714-cb2d23ba11a6",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "59badcb3-a785-495d-838d-c70d2d6518e6",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "59badcb3-f09a-4af4-a283-fb0b24b26e92",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "59de6f70-d7d6-47c1-b6a0-13cb6dd515ff",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5a037df3-635e-4df6-8af4-ec8275318933",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5a0b50e0-44d0-4b4b-a6b1-38113df39d88",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5a0b50e0-36d6-4bf0-a1db-2f22a3e9d78f",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5aafdd51-ab42-4b76-a54c-93b0487acf7c",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5aafdd51-efbd-47f0-8406-a9968589939c",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5b9ff0a8-dcea-4e58-81a3-633b8fbcc769",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "57c4bef6-d2d4-4631-a472-949250ad95a9",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "57c4befa-211c-4fc7-8776-afa8c36acea7",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5b85a50d-c3f5-4f3d-af89-8e6091c3bb80",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5b85a50d-dc01-41c9-bfc8-b8bda3c157ac",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5b85a5c2-5455-48fa-b406-5e1995c44d0f",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5b85a5c2-161a-470e-a7bb-f9aba23f8232",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5b982b93-10a0-401d-9434-0f864c73a36c",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5b982b93-586e-4bdc-8c7a-8ba1203290c2",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5b982b9e-4979-4be4-99af-0fcb6cf1c0e0",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5b982b9e-d337-466a-ac31-26d2a2312fbd",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5b982b9f-9c9b-47e3-aef4-b80e3bb5bfb6",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5b982b9f-8cdb-4677-997a-27670ec45e54",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5b982ba0-01fb-4727-a53f-898c1da0de34",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5b982ba0-ff2e-484b-981c-d089f41b49eb",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5b982fa8-0d9d-4605-bd7c-fb3e32f1e3ae",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5b99788e-fd51-4d4f-bd79-d9afbc97cb00",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5b99789e-167a-4de7-9822-a24928703c91",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5b99789e-b031-4da1-bbe4-138fd4a87d98",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5b997d49-1cfd-4070-9362-8fc2e121427b",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5b997def-46bd-4228-9980-2d64b38ac4be",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "54eaca29-5cb3-408b-8346-b06ec80b234c",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "54ecf571-9a4c-4f64-8ee2-4e4cc417ac93",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "54ecba60-256f-4912-8006-bdb50f0f1ad5",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "54e511fe-4d71-496b-afdc-db21afa1ec43",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "54e4cdd7-9a0b-4be4-8e85-2afc0f5e6291",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "54ed1b8d-c443-491b-bae6-809cec867da3",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "54e4cce0-9a5b-4980-9da3-6deb004e2cc5",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5b9ff0a8-a622-4736-b629-e5f9edd0be5b",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5bdb6d55-f69d-4353-ba2e-59557528abb9",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "5bdb6d67-3eca-4199-baa2-7dd040930a49",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5bdb6d67-b218-458d-a380-7055a0d36ea3",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5bdcb5f2-a964-4077-9b67-00a4c4b9050d",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5bdcb5f2-57db-49c7-be87-4c9e1ad6aa5a",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5bdcb65b-1c28-4e73-8180-8cdcdcaa8fe3",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "5be0c84d-1e3b-4e42-8ab1-e567b45accca",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5be0c84d-67d7-4370-8a15-5c0d211179fb",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5be1d26f-d3cc-4196-a7c4-9f9a29abfca6",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "5be20df5-1173-4193-9fd4-f0f4f79abc77",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5be20df5-ef54-4160-8714-97daac3d3e1b",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5be213bb-554a-4d57-a4f9-e9b0ca779287",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5be213bb-6a43-43de-866a-771116d1d420",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5be21400-8cf2-40a6-941e-b40fd8a3e65a",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "5c194221-48f3-4e37-9cf7-3d6a4205961f",
         :phi.element/type "fhir-type/MedicationPrescription"}
        {:fhir.Resource/id #uuid "5cbf73d6-4979-43e4-b345-5b3671815484",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5cbf73d6-412f-440d-beab-672e83f66da4",
          :phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5cbf742c-4484-4e9c-9c29-62b354aaf7d9",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:phi.element/type "fhir-type/Medication"}}
        {:fhir.Resource/id #uuid "5cbf7595-5389-4136-ab0d-cfb566857b5d",
         :phi.element/type "fhir-type/MedicationPrescription",
         :fhir.MedicationPrescription/medication
         {:fhir.Resource/id #uuid "5cbf7595-7fd2-458e-9982-f6598fad3aa2",
          :phi.element/type "fhir-type/Medication"}}])

(comment

  (require '[datomic.api :as d])
  (require '[blaze.fhir.transforms :as transforms])

  (def conn (d/connect (System/getenv "DATABASE_URI")))
  (def db (d/db conn))
  (def medication-request-mapping {:type                                   "MedicationPrescription"
                                   :fhir.MedicationPrescription/medication {:key   :fhir.MedicationRequest/medicationCodeableConcept
                                                                            :value 'blaze.fhir.transforms/medication-request-medication-reference->codeable-concept}
                                   })

  (into []
        (comp
         (map #(transforms/transform db medication-request-mapping %)))
        d)

  )
